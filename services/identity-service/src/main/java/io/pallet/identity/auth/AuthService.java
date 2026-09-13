package io.pallet.identity.auth;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.audit.AuditPublisher;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class AuthService {

    private static final String LOGIN_AUDIT_ACTION = "login";
    private static final String LOGINS_SUCCESS_METRIC = "identity.logins.success";
    private static final String LOGINS_FAILED_METRIC = "identity.logins.failed";
    private static final String LOGINS_UNVERIFIED_METRIC = "identity.logins.unverified";

    private final KeycloakTokenClient keycloakTokenClient;
    private final IdentityUserRepository identityUserRepository;
    private final AuditPublisher auditPublisher;
    private final MeterRegistry meterRegistry;

    AuthService(
            KeycloakTokenClient keycloakTokenClient,
            IdentityUserRepository identityUserRepository,
            AuditPublisher auditPublisher,
            MeterRegistry meterRegistry) {
        this.keycloakTokenClient = keycloakTokenClient;
        this.identityUserRepository = identityUserRepository;
        this.auditPublisher = auditPublisher;
        this.meterRegistry = meterRegistry;
    }

    TokenResponse login(String email, String password) {
        TokenResponse tokens;
        try {
            tokens = keycloakTokenClient.passwordGrant(email, password);
        } catch (EmailNotVerifiedException e) {
            meterRegistry.counter(LOGINS_UNVERIFIED_METRIC).increment();
            throw e;
        } catch (InvalidCredentialsException e) {
            meterRegistry.counter(LOGINS_FAILED_METRIC).increment();
            throw e;
        }
        meterRegistry.counter(LOGINS_SUCCESS_METRIC).increment();
        publishLoginAudit(email);
        return tokens;
    }

    TokenResponse refresh(String refreshToken) {
        return keycloakTokenClient.refreshGrant(refreshToken);
    }

    void logout(String refreshToken) {
        keycloakTokenClient.revokeSession(refreshToken);
    }

    private void publishLoginAudit(String email) {
        identityUserRepository
                .findByEmail(email)
                .ifPresent(user -> auditPublisher.publish(
                        user.getOrgId(),
                        user.getKeycloakUserId(),
                        LOGIN_AUDIT_ACTION,
                        "user:" + user.getId(),
                        Map.of()));
    }
}
