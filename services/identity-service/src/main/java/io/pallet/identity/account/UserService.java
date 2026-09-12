package io.pallet.identity.account;

import io.pallet.common.events.UserProfileUpdated;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.observability.Monitored;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.common.security.OrgContext;
import io.pallet.identity.audit.AuditPublisher;
import io.pallet.identity.auth.KeycloakTokenClient;
import io.pallet.identity.config.IdentityServiceProperties;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backs the authenticated {@code /users/me*} endpoints. Every method resolves "me" from the
 * caller's own token — a Keycloak user id ({@code sub}) or org id, never a path/body value — per
 * {@code docs/identity-service/ARCHITECTURE.md} §API.
 */
@Service
public class UserService {

    private static final String KEYCLOAK_ADMIN_POLICY = "keycloak-admin";
    private static final String PASSWORD_CHANGED_AUDIT_ACTION = "password-changed";
    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String REALM_ROLES_CLAIM = "roles";

    private final IdentityUserRepository identityUserRepository;
    private final IdentityServiceProperties properties;
    private final Keycloak keycloakAdminClient;
    private final ExternalCall externalCall;
    private final KeycloakTokenClient keycloakTokenClient;
    private final PlatformEventPublisher platformEventPublisher;
    private final AuditPublisher auditPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;

    UserService(
            IdentityUserRepository identityUserRepository,
            IdentityServiceProperties properties,
            Keycloak keycloakAdminClient,
            ExternalCall externalCall,
            KeycloakTokenClient keycloakTokenClient,
            PlatformEventPublisher platformEventPublisher,
            AuditPublisher auditPublisher,
            PlatformTransactionManager transactionManager,
            ApplicationEventPublisher applicationEventPublisher) {
        this.identityUserRepository = identityUserRepository;
        this.properties = properties;
        this.keycloakAdminClient = keycloakAdminClient;
        this.externalCall = externalCall;
        this.keycloakTokenClient = keycloakTokenClient;
        this.platformEventPublisher = platformEventPublisher;
        this.auditPublisher = auditPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public UserProfileResponse getProfile(Jwt jwt) {
        IdentityUser user = findByKeycloakUserId(jwt.getSubject());
        return new UserProfileResponse(
                jwt.getSubject(),
                OrgContext.requireOrgId(),
                realmRoles(jwt),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus());
    }

    public void updateProfile(Jwt jwt, String displayName) {
        IdentityUser user = findByKeycloakUserId(jwt.getSubject());
        transactionTemplate.executeWithoutResult(status -> {
            user.updateDisplayName(displayName);
            identityUserRepository.save(user);
            applicationEventPublisher.publishEvent(new UserProfileUpdatedEvent(
                    user.getOrgId(), user.getKeycloakUserId(), user.getEmail(), displayName));
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onUserProfileUpdated(UserProfileUpdatedEvent event) {
        platformEventPublisher.publish(
                UserProfileUpdated.of(event.orgId(), event.keycloakUserId(), event.email(), event.displayName()));
    }

    @Monitored
    public void changePassword(Jwt jwt, String currentPassword, String newPassword) {
        IdentityUser user = findByKeycloakUserId(jwt.getSubject());
        keycloakTokenClient.passwordGrant(user.getEmail(), currentPassword);
        resetKeycloakPassword(user.getKeycloakUserId(), newPassword);
        auditPublisher.publish(
                user.getOrgId(),
                user.getKeycloakUserId(),
                PASSWORD_CHANGED_AUDIT_ACTION,
                "user:" + user.getId(),
                Map.of());
    }

    private void resetKeycloakPassword(String keycloakUserId, String newPassword) {
        externalCall.run(
                KEYCLOAK_ADMIN_POLICY,
                () -> keycloakAdminClient
                        .realm(properties.keycloak().realm())
                        .users()
                        .get(keycloakUserId)
                        .resetPassword(passwordCredential(newPassword)));
    }

    private CredentialRepresentation passwordCredential(String password) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        return credential;
    }

    private IdentityUser findByKeycloakUserId(String keycloakUserId) {
        return identityUserRepository
                .findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new IllegalStateException("No local user row for Keycloak user " + keycloakUserId));
    }

    private static Set<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess == null || !(realmAccess.get(REALM_ROLES_CLAIM) instanceof Collection<?> roles)) {
            return Set.of();
        }
        return roles.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
    }
}
