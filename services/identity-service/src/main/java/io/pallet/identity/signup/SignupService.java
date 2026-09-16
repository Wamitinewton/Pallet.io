package io.pallet.identity.signup;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.error.ConflictException;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.observability.Monitored;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.identity.account.IdentityUser;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.audit.AuditPublisher;
import io.pallet.identity.config.IdentityServiceProperties;
import io.pallet.identity.verification.EmailVerificationService;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Orchestrates {@code POST /signup}: creates the Keycloak owner account before any local write,
 * then persists the local bootstrap record in one transaction. Creating the Keycloak user and
 * assigning it the owner role are two independent {@code ExternalCall} boundaries — each retried
 * on its own — so a transient failure in role assignment can never cause a retry to re-POST user
 * creation against an account that already exists. If either step fails after the Keycloak user
 * was created, or the local transaction fails after both steps succeed, a compensating delete
 * removes the orphaned account — the named, not-fully-closed dual-write gap
 * {@code docs/identity-service/ARCHITECTURE.md} describes.
 */
@Service
class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    private static final String KEYCLOAK_ADMIN_POLICY = "keycloak-admin";
    private static final String OWNER_ROLE = "owner";
    private static final String ORG_ID_ATTRIBUTE = "org_id";
    private static final String VERIFY_EMAIL_REQUIRED_ACTION = "VERIFY_EMAIL";
    private static final String WELCOME_NOTIFICATION_TYPE = "WELCOME";
    private static final String SIGNUP_AUDIT_ACTION = "sign-up";
    private static final String SIGNUPS_METRIC = "identity.signups";
    private static final String COMPENSATING_DELETE_METRIC = "identity.signups.compensating_delete";

    private final Keycloak keycloakAdminClient;
    private final IdentityServiceProperties properties;
    private final ExternalCall externalCall;
    private final OrgBootstrapRepository orgBootstrapRepository;
    private final IdentityUserRepository identityUserRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PlatformEventPublisher platformEventPublisher;
    private final AuditPublisher auditPublisher;
    private final EmailVerificationService emailVerificationService;
    private final MeterRegistry meterRegistry;

    SignupService(
            Keycloak keycloakAdminClient,
            IdentityServiceProperties properties,
            ExternalCall externalCall,
            OrgBootstrapRepository orgBootstrapRepository,
            IdentityUserRepository identityUserRepository,
            PlatformTransactionManager transactionManager,
            ApplicationEventPublisher applicationEventPublisher,
            PlatformEventPublisher platformEventPublisher,
            AuditPublisher auditPublisher,
            EmailVerificationService emailVerificationService,
            MeterRegistry meterRegistry) {
        this.keycloakAdminClient = keycloakAdminClient;
        this.properties = properties;
        this.externalCall = externalCall;
        this.orgBootstrapRepository = orgBootstrapRepository;
        this.identityUserRepository = identityUserRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.applicationEventPublisher = applicationEventPublisher;
        this.platformEventPublisher = platformEventPublisher;
        this.auditPublisher = auditPublisher;
        this.emailVerificationService = emailVerificationService;
        this.meterRegistry = meterRegistry;
    }

    @Monitored
    SignupResponse provision(SignupRequest request) {
        ensureSlugAndEmailAreAvailable(request);
        String orgId = UUID.randomUUID().toString();
        String keycloakUserId = createOwnerAccount(orgId, request);

        try {
            transactionTemplate.executeWithoutResult(status -> persistLocally(orgId, request, keycloakUserId));
        } catch (RuntimeException e) {
            compensateOrphanedKeycloakUser(keycloakUserId, e);
            throw e;
        }

        return new SignupResponse(orgId, request.organizationName(), request.slug());
    }

    boolean isSlugAvailable(String slug) {
        return !orgBootstrapRepository.existsBySlug(slug);
    }

    private void ensureSlugAndEmailAreAvailable(SignupRequest request) {
        if (orgBootstrapRepository.existsBySlug(request.slug())) {
            throw new ConflictException("An organization with slug '" + request.slug() + "' already exists");
        }
        if (identityUserRepository.existsByEmail(request.email())) {
            throw new ConflictException("An account with this email already exists");
        }
    }

    private String createOwnerAccount(String orgId, SignupRequest request) {
        String keycloakUserId = createKeycloakUser(orgId, request);
        try {
            assignOwnerRole(keycloakUserId);
        } catch (RuntimeException e) {
            compensateOrphanedKeycloakUser(keycloakUserId, e);
            throw e;
        }
        return keycloakUserId;
    }

    private String createKeycloakUser(String orgId, SignupRequest request) {
        return externalCall.call(KEYCLOAK_ADMIN_POLICY, () -> {
            UserRepresentation user = new UserRepresentation();
            user.setUsername(request.email());
            user.setEmail(request.email());
            user.setEnabled(true);
            user.setEmailVerified(false);
            user.setRequiredActions(List.of(VERIFY_EMAIL_REQUIRED_ACTION));
            user.singleAttribute(ORG_ID_ATTRIBUTE, orgId);
            user.setCredentials(List.of(ownerPasswordCredential(request.password())));

            try (Response response = keycloakAdminClient
                    .realm(properties.keycloak().realm())
                    .users()
                    .create(user)) {
                if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                    return recoverFromInDoubtCreate(orgId, request.email());
                }
                return CreatedResponseUtil.getCreatedId(response);
            }
        });
    }

    /**
     * A retry of this same policy can land here after an earlier create attempt's response was
     * lost even though Keycloak already applied it — retrying a non-idempotent POST is the
     * tradeoff of retrying at all. {@code org_id} is a fresh random value per sign-up attempt, so a
     * conflicting account carrying our own {@code orgId} is that earlier attempt, not a genuine
     * duplicate email; anything else is a real conflict.
     */
    private String recoverFromInDoubtCreate(String orgId, String email) {
        return keycloakAdminClient.realm(properties.keycloak().realm()).users().searchByEmail(email, true).stream()
                .filter(existing -> orgId.equals(existing.firstAttribute(ORG_ID_ATTRIBUTE)))
                .findFirst()
                .map(UserRepresentation::getId)
                .orElseThrow(() -> new ConflictException("An account with this email already exists"));
    }

    private CredentialRepresentation ownerPasswordCredential(String password) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        return credential;
    }

    private void assignOwnerRole(String keycloakUserId) {
        externalCall.run(KEYCLOAK_ADMIN_POLICY, () -> {
            RoleRepresentation ownerRole = keycloakAdminClient
                    .realm(properties.keycloak().realm())
                    .roles()
                    .get(OWNER_ROLE)
                    .toRepresentation();
            keycloakAdminClient
                    .realm(properties.keycloak().realm())
                    .users()
                    .get(keycloakUserId)
                    .roles()
                    .realmLevel()
                    .add(List.of(ownerRole));
        });
    }

    private void persistLocally(String orgId, SignupRequest request, String keycloakUserId) {
        orgBootstrapRepository.save(
                new OrgBootstrapRecord(orgId, request.organizationName(), request.slug(), keycloakUserId));
        IdentityUser user = identityUserRepository.save(
                new IdentityUser(orgId, keycloakUserId, request.email(), request.displayName()));
        applicationEventPublisher.publishEvent(new SignupCompletedEvent(
                orgId,
                request.organizationName(),
                request.slug(),
                user.getId(),
                keycloakUserId,
                request.email(),
                request.displayName()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onSignupCompleted(SignupCompletedEvent event) {
        platformEventPublisher.publish(OrgProvisioned.of(
                event.orgId(),
                event.orgName(),
                event.slug(),
                event.ownerUserId(),
                event.ownerEmail(),
                event.ownerDisplayName()));
        platformEventPublisher.publish(NotificationRequested.of(
                event.orgId(),
                WELCOME_NOTIFICATION_TYPE,
                event.ownerEmail(),
                null,
                null,
                Map.of("name", event.ownerDisplayName(), "orgName", event.orgName())));
        emailVerificationService.issueCode(
                identityUserRepository
                        .findById(event.userId())
                        .orElseThrow(() ->
                                new IllegalStateException("Sign-up user " + event.userId() + " vanished after commit")),
                EmailVerificationService.SOURCE_SIGNUP);
        auditPublisher.publish(
                event.orgId(),
                event.ownerUserId(),
                SIGNUP_AUDIT_ACTION,
                "organization:" + event.orgId(),
                Map.of("slug", event.slug()));
        meterRegistry.counter(SIGNUPS_METRIC).increment();
    }

    private void compensateOrphanedKeycloakUser(String keycloakUserId, RuntimeException cause) {
        meterRegistry.counter(COMPENSATING_DELETE_METRIC).increment();
        try {
            externalCall.run(
                    KEYCLOAK_ADMIN_POLICY,
                    () -> keycloakAdminClient
                            .realm(properties.keycloak().realm())
                            .users()
                            .get(keycloakUserId)
                            .remove());
        } catch (RuntimeException compensationFailure) {
            log.error(
                    "Compensating delete failed for orphaned Keycloak user {} after sign-up failure: {}",
                    keycloakUserId,
                    cause.getMessage(),
                    compensationFailure);
        }
    }
}
