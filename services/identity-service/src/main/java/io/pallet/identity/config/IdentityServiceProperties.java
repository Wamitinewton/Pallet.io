package io.pallet.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pallet.identity")
public record IdentityServiceProperties(
        @DefaultValue KeycloakAdmin keycloak,
        @DefaultValue EmailVerification emailVerification,
        @DefaultValue PasswordReset passwordReset) {

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
}
