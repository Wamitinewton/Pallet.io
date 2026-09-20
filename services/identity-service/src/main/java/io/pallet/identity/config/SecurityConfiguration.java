package io.pallet.identity.config;

import io.pallet.common.api.ApiPathProperties;
import io.pallet.common.security.PublicApiPaths;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Overrides {@code platform-common-security}'s authenticate-everything default: sign-up, login,
 * email verification and password reset are the account-bootstrap and recovery entry points and,
 * by definition, have no token to authenticate with. Every other route stays behind a valid Keycloak JWT.
 */
@Configuration
class SecurityConfiguration {

    private static final String[] PUBLIC_ACTUATOR_PATHS = {
        "/actuator/health/**", "/actuator/info", "/actuator/prometheus"
    };

    @Bean
    SecurityFilterChain identitySecurityFilterChain(
            HttpSecurity http,
            ApiPathProperties apiPathProperties,
            JwtAuthenticationConverter keycloakRoleConverter,
            List<PublicApiPaths> publicApiPaths,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler)
            throws Exception {
        String signupPath = apiPathProperties.prefix() + "/identity/signup";
        String slugAvailabilityPath = apiPathProperties.prefix() + "/identity/signup/slugs/*/availability";
        String resendVerificationPath = apiPathProperties.prefix() + "/identity/auth/email/resend-verification";
        String verifyEmailPath = apiPathProperties.prefix() + "/identity/auth/email/verify";
        String inviteAcceptPath = apiPathProperties.prefix() + "/identity/invites/*/accept";
        String loginPath = apiPathProperties.prefix() + "/identity/auth/login";
        String refreshPath = apiPathProperties.prefix() + "/identity/auth/refresh";
        String forgotPasswordPath = apiPathProperties.prefix() + "/identity/auth/password/forgot";
        String resetPasswordPath = apiPathProperties.prefix() + "/identity/auth/password/reset";
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PUBLIC_ACTUATOR_PATHS).permitAll();
                    auth.requestMatchers(
                                    HttpMethod.POST,
                                    signupPath,
                                    resendVerificationPath,
                                    verifyEmailPath,
                                    inviteAcceptPath,
                                    loginPath,
                                    refreshPath,
                                    forgotPasswordPath,
                                    resetPasswordPath)
                            .permitAll();
                    auth.requestMatchers(HttpMethod.GET, slugAvailabilityPath).permitAll();
                    publicApiPaths.forEach(paths -> auth.requestMatchers(paths.method(), paths.patterns())
                            .permitAll());
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRoleConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
