package io.pallet.apigateway.security;

import io.pallet.apigateway.config.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Authentication-only edge check: whether a valid Keycloak JWT is present, never what role it
 * carries, since the gateway executes no business logic to authorize. Backs off
 * {@code PalletResourceServerAutoConfiguration}'s authenticate-everything default so the routes'
 * own {@code public-paths} stay reachable without a token, per ADR-0012 decision 2 — this check is
 * additive to, never a replacement for, each backend's own independent resource-server chain.
 */
@Configuration
class GatewaySecurityConfiguration {

    private static final String[] PUBLIC_ACTUATOR_PATHS = {
        "/actuator/health/**", "/actuator/info", "/actuator/prometheus"
    };

    private static final String[] PUBLIC_INTERNAL_PATHS = {"/gateway-fallback/**"};

    private static final String[] PUBLIC_DOCS_PATHS = {"/docs", "/docs/**"};

    @Bean
    SecurityFilterChain gatewaySecurityFilterChain(HttpSecurity http, GatewayProperties properties) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(properties.allPublicPaths())
                        .permitAll()
                        .requestMatchers(PUBLIC_ACTUATOR_PATHS)
                        .permitAll()
                        .requestMatchers(PUBLIC_INTERNAL_PATHS)
                        .permitAll()
                        .requestMatchers(PUBLIC_DOCS_PATHS)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
