package io.pallet.common.test.containers;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Wires a service's resource-server security against {@link KeycloakContainerHolder}'s running
 * realm. Provides the {@link JwtDecoder} bean directly rather than only setting
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} and leaving Boot's own
 * conditional-on-that-property autoconfiguration to build it: a {@link DynamicPropertyRegistrar}
 * registers too late in the bean-factory lifecycle for {@code @ConditionalOnProperty} to see it,
 * so that autoconfiguration never fires and any eagerly-built {@code SecurityFilterChain} fails
 * with "No qualifying bean of type JwtDecoder". The issuer-uri property is still set for anything
 * that reads it directly (e.g. {@link KeycloakTestTokens}'s callers wiring their own client).
 *
 * <p>Use {@link KeycloakTestTokens} to fetch a real access token for one of the realm's two
 * fixture users once this is wired in.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KeycloakTestContainerConfiguration {

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder keycloakJwtDecoder() {
        return NimbusJwtDecoder.withIssuerLocation(issuerUri()).build();
    }

    @Bean
    DynamicPropertyRegistrar keycloakIssuerUriRegistrar() {
        return registry -> registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", this::issuerUri);
    }

    private String issuerUri() {
        return "http://" + KeycloakContainerHolder.CONTAINER.getHost() + ":"
                + KeycloakContainerHolder.CONTAINER.getMappedPort(KeycloakContainerHolder.HTTP_PORT) + "/realms/"
                + KeycloakContainerHolder.REALM;
    }
}
