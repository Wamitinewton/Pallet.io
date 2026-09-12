package io.pallet.identity.invite;

import io.pallet.common.error.ConflictException;
import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.observability.Monitored;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.identity.account.IdentityUser;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.audit.AuditPublisher;
import io.pallet.identity.config.IdentityServiceProperties;
import io.pallet.identity.config.InviteProperties;
import io.pallet.identity.token.SignedActionToken;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates {@code POST /invites/{token}/accept} — the sync-free handoff this service and
 * {@code org-team-service} share (see {@code docs/identity-service/ARCHITECTURE.md} §The boundary
 * problem this design solves). The token is minted and validated for org/role state entirely by
 * {@code org-team-service}; this service only verifies its signature and expiry, then creates the
 * Keycloak account.
 */
@Service
class InviteAcceptService {

    private static final Logger log = LoggerFactory.getLogger(InviteAcceptService.class);

    private static final String KEYCLOAK_ADMIN_POLICY = "keycloak-admin";
    private static final String INVITE_TOKEN_PURPOSE = "invite";
    private static final String INVITE_ACCEPTED_AUDIT_ACTION = "invite-accepted";

    private final Keycloak keycloakAdminClient;
    private final IdentityServiceProperties identityServiceProperties;
    private final InviteProperties inviteProperties;
    private final ExternalCall externalCall;
    private final ConsumedInviteTokenRepository consumedInviteTokenRepository;
    private final IdentityUserRepository identityUserRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PlatformEventPublisher platformEventPublisher;
    private final AuditPublisher auditPublisher;

    InviteAcceptService(
            Keycloak keycloakAdminClient,
            IdentityServiceProperties identityServiceProperties,
            InviteProperties inviteProperties,
            ExternalCall externalCall,
            ConsumedInviteTokenRepository consumedInviteTokenRepository,
            IdentityUserRepository identityUserRepository,
            PlatformTransactionManager transactionManager,
            ApplicationEventPublisher applicationEventPublisher,
            PlatformEventPublisher platformEventPublisher,
            AuditPublisher auditPublisher) {
        this.keycloakAdminClient = keycloakAdminClient;
        this.identityServiceProperties = identityServiceProperties;
        this.inviteProperties = inviteProperties;
        this.externalCall = externalCall;
        this.consumedInviteTokenRepository = consumedInviteTokenRepository;
        this.identityUserRepository = identityUserRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.applicationEventPublisher = applicationEventPublisher;
        this.platformEventPublisher = platformEventPublisher;
        this.auditPublisher = auditPublisher;
    }

    @Monitored
    void accept(String token, String password) {
        Map<String, String> claims =
                SignedActionToken.verify(INVITE_TOKEN_PURPOSE, token, inviteProperties.signingKey());
        String jti = claims.get("jti");
        String orgId = claims.get("orgId");
        String email = claims.get("email");
        String role = claims.get("role");
        String displayName = displayNameFrom(claims, email);

        if (consumedInviteTokenRepository.existsById(jti)) {
            throw new InviteAlreadyConsumedException(jti);
        }

        String keycloakUserId = createInvitedAccount(orgId, email, role, password);

        try {
            transactionTemplate.executeWithoutResult(
                    status -> persistLocally(orgId, jti, keycloakUserId, email, displayName, role));
        } catch (RuntimeException e) {
            // Closes the race the existsById pre-check above only narrows: two concurrent accepts
            // of the same token can both pass that check before either inserts, both create a
            // Keycloak user, and the loser hits consumed_invite_tokens' primary-key constraint
            // here instead. PersistenceExceptionHandler renders that as 409, same as the pre-check
            // hit; the compensating delete below removes the loser's now-orphaned Keycloak account.
            compensateOrphanedKeycloakUser(keycloakUserId, e);
            throw e;
        }
    }

    private String createInvitedAccount(String orgId, String email, String role, String password) {
        return externalCall.call(KEYCLOAK_ADMIN_POLICY, () -> {
            UserRepresentation user = new UserRepresentation();
            user.setUsername(email);
            user.setEmail(email);
            user.setEnabled(true);
            user.setEmailVerified(true);
            user.setRequiredActions(List.of());
            user.singleAttribute("org_id", orgId);
            user.setCredentials(List.of(passwordCredential(password)));

            try (Response response = keycloakAdminClient
                    .realm(identityServiceProperties.keycloak().realm())
                    .users()
                    .create(user)) {
                if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                    throw new ConflictException("An account with this email already exists");
                }
                String keycloakUserId = CreatedResponseUtil.getCreatedId(response);
                assignRole(keycloakUserId, role);
                return keycloakUserId;
            }
        });
    }

    private CredentialRepresentation passwordCredential(String password) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        return credential;
    }

    private void assignRole(String keycloakUserId, String role) {
        RoleRepresentation roleRepresentation = keycloakAdminClient
                .realm(identityServiceProperties.keycloak().realm())
                .roles()
                .get(role)
                .toRepresentation();
        keycloakAdminClient
                .realm(identityServiceProperties.keycloak().realm())
                .users()
                .get(keycloakUserId)
                .roles()
                .realmLevel()
                .add(List.of(roleRepresentation));
    }

    private void persistLocally(
            String orgId, String jti, String keycloakUserId, String email, String displayName, String role) {
        identityUserRepository.save(new IdentityUser(orgId, keycloakUserId, email, displayName));
        consumedInviteTokenRepository.save(new ConsumedInviteToken(jti));
        applicationEventPublisher.publishEvent(
                new InviteAcceptedEvent(orgId, jti, keycloakUserId, email, displayName, role));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onInviteAccepted(InviteAcceptedEvent event) {
        platformEventPublisher.publish(OrgInviteAccepted.of(
                event.orgId(), event.inviteId(), event.userId(), event.email(), event.displayName(), event.role()));
        auditPublisher.publish(
                event.orgId(),
                event.userId(),
                INVITE_ACCEPTED_AUDIT_ACTION,
                "user:" + event.userId(),
                Map.of("role", event.role()));
    }

    private void compensateOrphanedKeycloakUser(String keycloakUserId, RuntimeException cause) {
        try {
            externalCall.run(
                    KEYCLOAK_ADMIN_POLICY,
                    () -> keycloakAdminClient
                            .realm(identityServiceProperties.keycloak().realm())
                            .users()
                            .get(keycloakUserId)
                            .remove());
        } catch (RuntimeException compensationFailure) {
            log.error(
                    "Compensating delete failed for orphaned Keycloak user {} after invite-accept failure: {}",
                    keycloakUserId,
                    cause.getMessage(),
                    compensationFailure);
        }
    }

    private static String displayNameFrom(Map<String, String> claims, String email) {
        String claimed = claims.get("displayName");
        if (claimed != null && !claimed.isBlank()) {
            return claimed;
        }
        return humanize(email.substring(0, email.indexOf('@')));
    }

    private static String humanize(String localPart) {
        StringBuilder result = new StringBuilder();
        for (String word : localPart.split("[._-]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.isEmpty() ? localPart : result.toString();
    }
}
