package io.pallet.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pallet.identity")
public record IdentityServiceProperties(@DefaultValue KeycloakAdmin keycloak) {

    public record KeycloakAdmin(String serverUrl, String realm, String adminClientId, String adminClientSecret) {}
}
