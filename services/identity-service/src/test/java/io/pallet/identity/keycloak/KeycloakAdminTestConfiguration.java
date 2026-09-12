package io.pallet.identity.keycloak;

import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Points {@code pallet.identity.keycloak.*} at the same running fixture container
 * {@link KeycloakTestContainerConfiguration} wires the resource-server side against, using its
 * {@code pallet-admin-client} service account — a confidential client scoped to
 * {@code realm-management.manage-users}, so {@code KeycloakAdminConfiguration}'s bean can make
 * real Admin API calls in a test the same way it does in production.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KeycloakAdminTestConfiguration {

    @Bean
    DynamicPropertyRegistrar identityKeycloakAdminPropertiesRegistrar() {
        return registry -> {
            registry.add("pallet.identity.keycloak.server-url", KeycloakTestContainerConfiguration::serverUrl);
            registry.add("pallet.identity.keycloak.realm", KeycloakTestContainerConfiguration::realm);
            registry.add(
                    "pallet.identity.keycloak.admin-client-id",
                    () -> KeycloakTestContainerConfiguration.ADMIN_CLIENT_ID);
            registry.add(
                    "pallet.identity.keycloak.admin-client-secret",
                    () -> KeycloakTestContainerConfiguration.ADMIN_CLIENT_SECRET);
        };
    }
}
