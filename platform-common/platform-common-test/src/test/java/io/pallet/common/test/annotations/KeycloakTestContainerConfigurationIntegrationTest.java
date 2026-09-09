package io.pallet.common.test.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.containers.KeycloakTestTokens;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Proves {@code KeycloakTestContainerConfiguration} wires a real, reachable Keycloak realm end to
 * end: a real access token, fetched from the container via the password grant, is accepted by a
 * standard Spring Security OAuth2 resource-server filter chain, not a mock decoder.
 *
 * <p>{@code SecuredFixtureConfig} is test-local rather than a dependency on
 * {@code platform-common-security}: that module's {@code PalletResourceServerAutoConfiguration}
 * would apply to every test in this shared module, not just this one. Its realm-role →
 * {@code ROLE_*} mapping mirrors {@code PalletResourceServerAutoConfiguration}'s own converter so
 * the assertions mean something for a real Pallet token shape.
 */
@IntegrationTest
@AutoConfigureTestRestTemplate
@Import({
    KeycloakTestContainerConfiguration.class,
    KeycloakTestContainerConfigurationIntegrationTest.SecuredFixtureConfig.class
})
class KeycloakTestContainerConfigurationIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void anUnauthenticatedRequestIsRejected() {
        ResponseEntity<String> response = restTemplate.getForEntity("/secured/whoami", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aRealTokenForTheOwnerFixtureUserCarriesItsOrgIdAndRealmRoles() {
        String token = KeycloakTestTokens.passwordGrantToken(
                KeycloakTestTokens.OWNER_USERNAME, KeycloakTestTokens.FIXTURE_PASSWORD);

        ResponseEntity<WhoAmI> response =
                restTemplate.exchange("/secured/whoami", HttpMethod.GET, bearer(token), WhoAmI.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().orgId()).isEqualTo("org-test-1");
        assertThat(response.getBody().roles()).contains("ROLE_owner", "ROLE_admin");
    }

    @Test
    void aRealTokenForTheViewerFixtureUserCarriesOnlyItsOwnOrgIdAndRole() {
        String token = KeycloakTestTokens.passwordGrantToken(
                KeycloakTestTokens.VIEWER_USERNAME, KeycloakTestTokens.FIXTURE_PASSWORD);

        ResponseEntity<WhoAmI> response =
                restTemplate.exchange("/secured/whoami", HttpMethod.GET, bearer(token), WhoAmI.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().orgId()).isEqualTo("org-test-2");
        assertThat(response.getBody().roles()).contains("ROLE_viewer").doesNotContain("ROLE_owner", "ROLE_admin");
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private record WhoAmI(String orgId, List<String> roles) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class SecuredFixtureConfig {

        @Bean
        SecurityFilterChain fixtureSecurityFilterChain(HttpSecurity http) {
            http.csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .oauth2ResourceServer(
                            oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(realmRoleConverter())));
            return http.build();
        }

        private JwtAuthenticationConverter realmRoleConverter() {
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(jwt -> {
                Collection<GrantedAuthority> authorities = new ArrayList<>();
                if (jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess
                        && realmAccess.get("roles") instanceof Collection<?> roles) {
                    roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
                }
                return authorities;
            });
            return converter;
        }
    }
}
