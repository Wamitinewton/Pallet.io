package io.pallet.common.security;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

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

    private static final Logger log = LoggerFactory.getLogger(PalletResourceServerAutoConfiguration.class);
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
    SecurityFilterChain palletSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter keycloakRoleConverter,
            List<PublicApiPaths> publicApiPaths,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PUBLIC_PATHS).permitAll();
                    publicApiPaths.forEach(paths -> auth.requestMatchers(paths.method(), paths.patterns())
                            .permitAll());
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRoleConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * A missing/invalid/expired/revoked token, or one lacking a required role, is rejected inside
     * this filter chain — before the {@code DispatcherServlet} ever runs — so
     * {@code SecurityExceptionHandler}'s {@code @RestControllerAdvice} never gets a chance to
     * render it. These two beans are the only place that failure gets an {@code ErrorResponse}
     * body; a service overriding the filter chain (see {@code identitySecurityFilterChain},
     * {@code gatewaySecurityFilterChain}) wires the same two beans in for itself.
     */
    @Bean
    @ConditionalOnMissingBean
    AuthenticationEntryPoint palletAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new PalletAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    AccessDeniedHandler palletAccessDeniedHandler(ObjectMapper objectMapper) {
        return new PalletAccessDeniedHandler(objectMapper);
    }

    /**
     * Contributed as a plain {@code OAuth2TokenValidator<Jwt>} bean — Spring Boot's own
     * {@code JwtDecoderConfiguration} collects every bean of this type and composes it with the
     * standard timestamp/issuer validators on whichever {@code JwtDecoder} it builds (issuer-uri,
     * jwk-set-uri, or public key), so no service needs its own {@code JwtDecoder} bean just to add
     * this check.
     */
    @Bean
    @ConditionalOnMissingBean
    OAuth2TokenValidator<Jwt> sessionRevocationValidator(RevokedSessionRegistry revokedSessionRegistry) {
        return new SessionRevocationValidator(revokedSessionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(RevokedSessionRegistry.class)
    RevokedSessionRegistry inMemoryRevokedSessionRegistry() {
        log.warn("Using the in-memory RevokedSessionRegistry: a revocation is only visible on the instance "
                + "that wrote it. Add spring-data-redis, or override the bean, before scaling a service out.");
        return new InMemoryRevokedSessionRegistry();
    }

    // Isolated in its own nested, classpath-gated configuration for the same reason
    // PalletMessagingAutoConfiguration.RedisIdempotencyGuardConfiguration is: a StringRedisTemplate-typed
    // bean method directly on this class would fail reflection once a service omits spring-data-redis
    // entirely, not merely leaves its autoconfiguration disabled.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    static class RedisRevokedSessionRegistryConfiguration {

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean(RevokedSessionRegistry.class)
        RevokedSessionRegistry redisRevokedSessionRegistry(
                StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
            return new RedisRevokedSessionRegistry(redisTemplate, meterRegistry);
        }
    }

    /**
     * Maps Keycloak {@code realm_access.roles} onto {@code ROLE_*} authorities so
     * {@code hasRole(...)} works, keeping the default scope authorities too. Exposed as a bean
     * (rather than kept private) so a service defining its own {@link SecurityFilterChain} — to
     * add public routes this default chain doesn't allow for — can still reuse the same
     * role-mapping instead of re-deriving it.
     */
    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter keycloakRoleConverter() {
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
