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
 * fixture users once this is wired in. A service whose own Keycloak Admin client needs to point
 * at this same container (rather than only validating tokens against it) uses {@link #serverUrl()}
 * and {@link #realm()} plus the {@link #ADMIN_CLIENT_ID} / {@link #ADMIN_CLIENT_SECRET} fixture
 * credentials — a confidential client with a service account holding
 * {@code realm-management.manage-users}, the same least-privilege scope the real realm grants
 * {@code identity-service} in production.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KeycloakTestContainerConfiguration {

    /**
     * Confidential client id of the fixture realm's service account, scoped to
     * {@code realm-management.manage-users}.
     */
    public static final String ADMIN_CLIENT_ID = "pallet-admin-client";

    public static final String ADMIN_CLIENT_SECRET = "pallet-admin-client-secret";

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder keycloakJwtDecoder() {
        return NimbusJwtDecoder.withIssuerLocation(issuerUri()).build();
    }

    @Bean
    DynamicPropertyRegistrar keycloakIssuerUriRegistrar() {
        return registry -> registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", this::issuerUri);
    }

    /**
     * Base URL of the running fixture container, e.g. {@code http://localhost:54321} — the value a
     * {@code KeycloakBuilder.serverUrl(...)} call needs, as opposed to {@link #issuerUri()}'s
     * realm-qualified form.
     */
    public static String serverUrl() {
        return "http://" + KeycloakContainerHolder.CONTAINER.getHost() + ":"
                + KeycloakContainerHolder.CONTAINER.getMappedPort(KeycloakContainerHolder.HTTP_PORT);
    }

    public static String realm() {
        return KeycloakContainerHolder.REALM;
    }

    private String issuerUri() {
        return serverUrl() + "/realms/" + KeycloakContainerHolder.REALM;
    }
}
