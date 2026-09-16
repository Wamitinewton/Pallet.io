package io.pallet.identity.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.pallet.identity.config.IdentityServiceProperties;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Non-blocking rate check guarding identity-service's unauthenticated, abuse-shaped endpoints
 * (sign-up, slug availability, login, email verification). One Resilience4j {@code RateLimiter} per key, created
 * lazily by the registry on first use, so one caller exhausting its budget never touches
 * another's. {@code timeoutDuration} is {@code ZERO} so {@link #tryAcquire(String)} always
 * returns immediately.
 */
@Component
public class AuthRateLimiter {

    private static final String CONFIG_NAME = "identity-auth-rate-limit";

    private final RateLimiterRegistry registry;

    AuthRateLimiter(IdentityServiceProperties properties) {
        IdentityServiceProperties.RateLimit rateLimit = properties.rateLimit();
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(rateLimit.permitsPerPeriod())
                .limitRefreshPeriod(rateLimit.period())
                .timeoutDuration(Duration.ZERO)
                .build();
        this.registry = RateLimiterRegistry.of(Map.of(CONFIG_NAME, config));
    }

    public boolean tryAcquire(String key) {
        return registry.rateLimiter(key, CONFIG_NAME).acquirePermission();
    }
}
