package io.pallet.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pallet.identity")
public record IdentityServiceProperties(
        @DefaultValue KeycloakAdmin keycloak,
        @DefaultValue EmailVerification emailVerification,
        @DefaultValue PasswordReset passwordReset,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue SessionRevocation sessionRevocation) {

    public record KeycloakAdmin(
            String serverUrl,
            String realm,
            String adminClientId,
            String adminClientSecret,
            @DefaultValue("pallet-dashboard") String tokenClientId) {}

    public record EmailVerification(
            @DefaultValue("PT15M") Duration codeTtl,
            @DefaultValue("5") int maxAttempts) {}

    public record PasswordReset(
            @DefaultValue("PT1H") Duration tokenTtl,
            @DefaultValue("http://localhost:5173") String dashboardBaseUrl) {}

    /**
     * Per-(client address, endpoint) budget guarding {@code /identity/signup}, {@code /identity/auth/login},
     * {@code /identity/auth/email/verify} and {@code /identity/auth/email/resend-verification} — see
     * {@code io.pallet.identity.ratelimit.AuthRateLimiter}.
     */
    public record RateLimit(
            @DefaultValue("20") int permitsPerPeriod,
            @DefaultValue("PT1M") Duration period) {}

    /**
     * How long a revoked session's marker outlives the revocation call — must cover the realm's
     * access token lifespan (see {@code infra/keycloak/pallet-realm.json}'s {@code accessTokenLifespan}),
     * since a token minted moments before revocation stays otherwise-valid for its full lifetime.
     */
    public record SessionRevocation(@DefaultValue("PT15M") Duration retention) {}
}
