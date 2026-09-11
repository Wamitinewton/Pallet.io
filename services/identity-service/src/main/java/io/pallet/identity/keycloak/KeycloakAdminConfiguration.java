package io.pallet.identity.keycloak;

import io.pallet.identity.config.IdentityServiceProperties;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The bean is a plain admin client - no {@code ExternalCall} wrapping here. Different Admin
 * operations need different named resilience policies ({@code keycloak-admin} for CRUD,
 * {@code keycloak-token} for the token/introspection endpoint), so wrapping happens at each call
 * site instead of once at construction.
 */
@Configuration
class KeycloakAdminConfiguration {

    @Bean
    Keycloak keycloakAdminClient(IdentityServiceProperties props) {
        return KeycloakBuilder.builder()
                .serverUrl(props.keycloak().serverUrl())
                .realm(props.keycloak().realm())
                .clientId(props.keycloak().adminClientId())
                .clientSecret(props.keycloak().adminClientSecret())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
    }
}
