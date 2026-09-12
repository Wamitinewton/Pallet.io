package io.pallet.identity.verification;

import io.pallet.common.events.NotificationRequested;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.observability.Monitored;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.identity.account.IdentityUser;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.audit.AuditPublisher;
import io.pallet.identity.config.IdentityServiceProperties;
import io.pallet.identity.token.InvalidTokenException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mints, resends and confirms the 8-character OTP that gates a self-registered account's ability
 * to authenticate. {@code issueCode} is the one place a code is ever minted — called both from
 * {@code SignupService}'s post-commit step and from {@link #resend(String)}.
 */
@Service
public class EmailVerificationService {

    private static final String KEYCLOAK_ADMIN_POLICY = "keycloak-admin";
    private static final String EMAIL_VERIFICATION_NOTIFICATION_TYPE = "EMAIL_VERIFICATION";
    private static final String EMAIL_VERIFIED_AUDIT_ACTION = "email-verified";

    private final EmailVerificationCodeRepository codeRepository;
    private final IdentityUserRepository identityUserRepository;
    private final IdentityServiceProperties properties;
    private final Keycloak keycloakAdminClient;
    private final ExternalCall externalCall;
    private final PlatformEventPublisher platformEventPublisher;
    private final AuditPublisher auditPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;

    EmailVerificationService(
            EmailVerificationCodeRepository codeRepository,
            IdentityUserRepository identityUserRepository,
            IdentityServiceProperties properties,
            Keycloak keycloakAdminClient,
            ExternalCall externalCall,
            PlatformEventPublisher platformEventPublisher,
            AuditPublisher auditPublisher,
            PlatformTransactionManager transactionManager,
            ApplicationEventPublisher applicationEventPublisher) {
        this.codeRepository = codeRepository;
        this.identityUserRepository = identityUserRepository;
        this.properties = properties;
        this.keycloakAdminClient = keycloakAdminClient;
        this.externalCall = externalCall;
        this.platformEventPublisher = platformEventPublisher;
        this.auditPublisher = auditPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Monitored
    public void issueCode(IdentityUser user) {
        String rawCode = VerificationCodeGenerator.generate();
        Duration ttl = properties.emailVerification().codeTtl();
        transactionTemplate.executeWithoutResult(status -> {
            codeRepository.deleteByUserIdAndConsumedAtIsNull(user.getId());
            codeRepository.save(new EmailVerificationCode(
                    user.getId(), hash(rawCode), Instant.now().plus(ttl)));
        });
        platformEventPublisher.publish(NotificationRequested.of(
                user.getOrgId(),
                EMAIL_VERIFICATION_NOTIFICATION_TYPE,
                user.getEmail(),
                null,
                null,
                Map.of("code", rawCode, "expiresInMinutes", ttl.toMinutes())));
    }

    public void resend(String email) {
        identityUserRepository.findByEmail(email).ifPresent(this::issueCode);
    }

    @Monitored
    public void verify(String email, String rawCode) {
        IdentityUser user = identityUserRepository
                .findByEmail(email)
                .orElseThrow(() -> new InvalidTokenException("No account for email " + email));
        EmailVerificationCode code = findActiveCode(user);

        if (code.getAttempts() >= properties.emailVerification().maxAttempts()) {
            codeRepository.delete(code);
            throw new InvalidTokenException("Email verification code attempt budget exhausted");
        }

        if (!codeMatches(rawCode, code)) {
            code.incrementAttempts();
            codeRepository.save(code);
            throw new InvalidTokenException("Email verification code mismatch");
        }

        markEmailVerifiedInKeycloak(user.getKeycloakUserId());
        transactionTemplate.executeWithoutResult(status -> {
            code.consume();
            codeRepository.save(code);
            applicationEventPublisher.publishEvent(
                    new EmailVerifiedEvent(user.getOrgId(), user.getId(), user.getKeycloakUserId()));
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onEmailVerified(EmailVerifiedEvent event) {
        auditPublisher.publish(
                event.orgId(), event.keycloakUserId(), EMAIL_VERIFIED_AUDIT_ACTION, "user:" + event.userId(), Map.of());
    }

    private EmailVerificationCode findActiveCode(IdentityUser user) {
        return codeRepository
                .findByUserIdAndConsumedAtIsNull(user.getId())
                .filter(code -> !code.isExpired())
                .orElseThrow(
                        () -> new InvalidTokenException("No active email verification code for user " + user.getId()));
    }

    private boolean codeMatches(String rawCode, EmailVerificationCode code) {
        return MessageDigest.isEqual(
                hash(rawCode).getBytes(StandardCharsets.UTF_8),
                code.getCodeHash().getBytes(StandardCharsets.UTF_8));
    }

    private void markEmailVerifiedInKeycloak(String keycloakUserId) {
        externalCall.run(KEYCLOAK_ADMIN_POLICY, () -> {
            UserResource userResource = keycloakAdminClient
                    .realm(properties.keycloak().realm())
                    .users()
                    .get(keycloakUserId);
            UserRepresentation representation = userResource.toRepresentation();
            representation.setEmailVerified(true);
            representation.setRequiredActions(List.of());
            userResource.update(representation);
        });
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
