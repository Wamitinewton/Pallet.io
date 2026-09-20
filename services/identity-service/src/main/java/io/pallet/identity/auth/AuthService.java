package io.pallet.identity.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.error.ForbiddenException;
import io.pallet.common.security.RevokedSessionRegistry;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.audit.AuditPublisher;
import io.pallet.identity.config.IdentityServiceProperties;
import java.text.ParseException;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;
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
    private final RevokedSessionRegistry revokedSessionRegistry;
    private final IdentityServiceProperties properties;

    AuthService(
            KeycloakTokenClient keycloakTokenClient,
            IdentityUserRepository identityUserRepository,
            AuditPublisher auditPublisher,
            MeterRegistry meterRegistry,
            RevokedSessionRegistry revokedSessionRegistry,
            IdentityServiceProperties properties) {
        this.keycloakTokenClient = keycloakTokenClient;
        this.identityUserRepository = identityUserRepository;
        this.auditPublisher = auditPublisher;
        this.meterRegistry = meterRegistry;
        this.revokedSessionRegistry = revokedSessionRegistry;
        this.properties = properties;
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

    void logout(Jwt caller, String refreshToken) {
        String sessionId = sessionOwnedByCaller(caller, refreshToken);
        keycloakTokenClient.revokeSession(refreshToken);
        if (sessionId != null) {
            revokedSessionRegistry.revoke(
                    sessionId, properties.sessionRevocation().retention());
        }
    }

    /**
     * Keycloak validates the refresh token itself on logout; this only checks it belongs to the
     * authenticated caller, so a leaked refresh token can't be revoked by a different account.
     */
    private static String sessionOwnedByCaller(Jwt caller, String refreshToken) {
        JWTClaimsSet claims;
        try {
            claims = JWTParser.parse(refreshToken).getJWTClaimsSet();
        } catch (ParseException e) {
            throw new InvalidCredentialsException("Malformed refresh token on logout");
        }
        if (!caller.getSubject().equals(claims.getSubject())) {
            throw new ForbiddenException("This refresh token does not belong to the caller");
        }
        String sessionId = claims.getClaim("sid") instanceof String sid ? sid : null;
        return sessionId != null ? sessionId : caller.getClaimAsString("sid");
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
