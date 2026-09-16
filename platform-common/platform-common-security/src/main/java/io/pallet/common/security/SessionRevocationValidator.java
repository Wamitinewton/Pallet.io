package io.pallet.common.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a token whose Keycloak session ({@code sid} claim) has been revoked, closing the gap a
 * stateless JWT otherwise leaves open: deleting the Keycloak session stops new tokens from being
 * issued but does nothing to ones already handed out. Contributed as an
 * {@code OAuth2TokenValidator<Jwt>} bean, Spring Boot's own {@code JwtDecoderConfiguration} picks
 * it up automatically and composes it with the standard timestamp/issuer validators — no
 * {@code JwtDecoder} bean of our own required.
 */
public class SessionRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private static final String SESSION_ID_CLAIM = "sid";
    private static final OAuth2Error SESSION_REVOKED_ERROR =
            new OAuth2Error("session_revoked", "The session behind this token has been revoked.", null);

    private final RevokedSessionRegistry registry;

    public SessionRevocationValidator(RevokedSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String sessionId = token.getClaimAsString(SESSION_ID_CLAIM);
        if (sessionId != null && registry.isRevoked(sessionId)) {
            return OAuth2TokenValidatorResult.failure(SESSION_REVOKED_ERROR);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
