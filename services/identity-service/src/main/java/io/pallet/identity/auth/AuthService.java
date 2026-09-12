package io.pallet.identity.auth;

import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.audit.AuditPublisher;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class AuthService {

    private static final String LOGIN_AUDIT_ACTION = "login";

    private final KeycloakTokenClient keycloakTokenClient;
    private final IdentityUserRepository identityUserRepository;
    private final AuditPublisher auditPublisher;

    AuthService(
            KeycloakTokenClient keycloakTokenClient,
            IdentityUserRepository identityUserRepository,
            AuditPublisher auditPublisher) {
        this.keycloakTokenClient = keycloakTokenClient;
        this.identityUserRepository = identityUserRepository;
        this.auditPublisher = auditPublisher;
    }

    TokenResponse login(String email, String password) {
        TokenResponse tokens = keycloakTokenClient.passwordGrant(email, password);
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
