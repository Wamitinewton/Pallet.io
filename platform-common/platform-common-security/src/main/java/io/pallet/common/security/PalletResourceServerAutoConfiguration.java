package io.pallet.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Secure-by-default resource server baseline for every Pallet service.
 *
 * <p>Every request needs a valid Keycloak-issued JWT except the actuator
 * liveness and readiness probes and the Prometheus scrape endpoint. A service
 * that needs a different rule set defines its own {@link SecurityFilterChain}
 * bean and this configuration backs off.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
public class PalletResourceServerAutoConfiguration {

    private static final String[] PUBLIC_PATHS = {"/actuator/health/**", "/actuator/info", "/actuator/prometheus"};

    private static Collection<?> realmRoles(Jwt jwt) {
        if (jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> roles) {
            return roles;
        }
        return java.util.List.of();
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain palletSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRoleConverter())));
        return http.build();
    }

    /**
     * Maps Keycloak {@code realm_access.roles} onto {@code ROLE_*} authorities
     * so {@code hasRole(...)} works, keeping the default scope authorities too.
     */
    private JwtAuthenticationConverter keycloakRoleConverter() {
        JwtGrantedAuthoritiesConverter scopeAuthorities = new JwtGrantedAuthoritiesConverter();

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopeAuthorities.convert(jwt));
            realmRoles(jwt).forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            return authorities;
        });
        return converter;
    }
}
