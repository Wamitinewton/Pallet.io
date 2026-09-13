package io.pallet.identity.ratelimit;

import io.pallet.common.api.ApiPathProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies {@link AuthRateLimitInterceptor} to identity-service's unauthenticated, abuse-shaped
 * endpoints only — {@code ARCHITECTURE.md}'s checkpoint 12 rate-limiting decision. Every other
 * route, including the authenticated self-service ones, is unaffected.
 */
@Configuration
class RateLimitWebConfiguration implements WebMvcConfigurer {

    private final AuthRateLimiter rateLimiter;
    private final ApiPathProperties apiPathProperties;

    RateLimitWebConfiguration(AuthRateLimiter rateLimiter, ApiPathProperties apiPathProperties) {
        this.rateLimiter = rateLimiter;
        this.apiPathProperties = apiPathProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        String prefix = apiPathProperties.prefix();
        registry.addInterceptor(new AuthRateLimitInterceptor(rateLimiter))
                .addPathPatterns(
                        prefix + "/signup",
                        prefix + "/auth/login",
                        prefix + "/auth/email/verify",
                        prefix + "/auth/email/resend-verification");
    }
}
