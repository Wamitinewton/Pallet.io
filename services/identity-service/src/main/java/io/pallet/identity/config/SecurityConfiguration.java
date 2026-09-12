package io.pallet.identity.config;

import io.pallet.common.api.ApiPathProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Overrides {@code platform-common-security}'s authenticate-everything default: sign-up is the
 * platform's account-bootstrap entry point and, by definition, has no token to authenticate with
 * yet. Every other route stays behind a valid Keycloak JWT.
 */
@Configuration
class SecurityConfiguration {

    private static final String[] PUBLIC_ACTUATOR_PATHS = {
        "/actuator/health/**", "/actuator/info", "/actuator/prometheus"
    };

    @Bean
    SecurityFilterChain identitySecurityFilterChain(
            HttpSecurity http, ApiPathProperties apiPathProperties, JwtAuthenticationConverter keycloakRoleConverter)
            throws Exception {
        String signupPath = apiPathProperties.prefix() + "/signup";
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_ACTUATOR_PATHS)
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, signupPath)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRoleConverter)));
        return http.build();
    }
}
