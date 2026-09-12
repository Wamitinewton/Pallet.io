package io.pallet.identity.account;

import io.pallet.common.events.NotificationRequested;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.observability.Monitored;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.identity.audit.AuditPublisher;
import io.pallet.identity.config.IdentityServiceProperties;
import io.pallet.identity.token.InvalidTokenException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordResetService {

    private static final String KEYCLOAK_ADMIN_POLICY = "keycloak-admin";
    private static final String PASSWORD_RESET_NOTIFICATION_TYPE = "PASSWORD_RESET";
    private static final String PASSWORD_RESET_AUDIT_ACTION = "password-reset-completed";
    private static final String RESET_PASSWORD_PATH = "/reset-password";
    private static final int RAW_TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OneTimeActionTokenRepository tokenRepository;
    private final IdentityUserRepository identityUserRepository;
    private final IdentityServiceProperties properties;
    private final Keycloak keycloakAdminClient;
    private final ExternalCall externalCall;
    private final PlatformEventPublisher platformEventPublisher;
    private final AuditPublisher auditPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;

    PasswordResetService(
            OneTimeActionTokenRepository tokenRepository,
            IdentityUserRepository identityUserRepository,
            IdentityServiceProperties properties,
            Keycloak keycloakAdminClient,
            ExternalCall externalCall,
            PlatformEventPublisher platformEventPublisher,
            AuditPublisher auditPublisher,
            PlatformTransactionManager transactionManager,
            ApplicationEventPublisher applicationEventPublisher) {
        this.tokenRepository = tokenRepository;
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
    public void requestReset(String email) {
        identityUserRepository.findByEmail(email).ifPresent(this::issueResetToken);
    }

    @Monitored
    public void completeReset(String rawToken, String newPassword) {
        OneTimeActionToken token = findValidToken(rawToken);
        IdentityUser user = identityUserRepository
                .findById(token.getUserId())
                .orElseThrow(() -> new InvalidTokenException("Password reset token references a missing user"));

        resetKeycloakPassword(user.getKeycloakUserId(), newPassword);
        transactionTemplate.executeWithoutResult(status -> {
            token.markUsed();
            tokenRepository.save(token);
            applicationEventPublisher.publishEvent(
                    new PasswordResetCompletedEvent(user.getOrgId(), user.getId(), user.getKeycloakUserId()));
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onPasswordResetCompleted(PasswordResetCompletedEvent event) {
        auditPublisher.publish(
                event.orgId(), event.keycloakUserId(), PASSWORD_RESET_AUDIT_ACTION, "user:" + event.userId(), Map.of());
    }

    private void issueResetToken(IdentityUser user) {
        String rawToken = generateRawToken();
        Duration ttl = properties.passwordReset().tokenTtl();
        transactionTemplate.executeWithoutResult(status -> tokenRepository.save(new OneTimeActionToken(
                user.getId(),
                TokenPurpose.PASSWORD_RESET,
                hash(rawToken),
                Instant.now().plus(ttl))));
        platformEventPublisher.publish(NotificationRequested.of(
                user.getOrgId(),
                PASSWORD_RESET_NOTIFICATION_TYPE,
                user.getEmail(),
                null,
                null,
                Map.of("resetUrl", buildResetUrl(rawToken))));
    }

    private OneTimeActionToken findValidToken(String rawToken) {
        return tokenRepository
                .findByTokenHashAndPurpose(hash(rawToken), TokenPurpose.PASSWORD_RESET)
                .filter(token -> token.getUsedAt() == null)
                .filter(token -> !token.isExpired())
                .orElseThrow(() -> new InvalidTokenException("Password reset token is missing, used, or expired"));
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

    private String buildResetUrl(String rawToken) {
        return properties.passwordReset().dashboardBaseUrl() + RESET_PASSWORD_PATH + "?token=" + rawToken;
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[RAW_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
