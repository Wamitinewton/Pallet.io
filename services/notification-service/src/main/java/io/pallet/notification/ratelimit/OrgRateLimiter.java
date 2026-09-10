package io.pallet.notification.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.pallet.notification.config.NotificationServiceProperties;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Non-blocking per-org send permission check. One Resilience4j {@code RateLimiter} instance per
 * {@code orgId}, created lazily by the registry on first use, so one org exhausting its budget
 * never touches another org's. {@code timeoutDuration} is {@code ZERO} so
 * {@link #tryAcquire(String)} always returns immediately, even if a waiting Resilience4j variant
 * is ever used against this config by mistake.
 */
@Component
public class OrgRateLimiter {

    private static final String CONFIG_NAME = "notification-org-rate-limit";

    private final RateLimiterRegistry registry;

    OrgRateLimiter(NotificationServiceProperties properties) {
        NotificationServiceProperties.RateLimit rateLimit = properties.rateLimit();
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(rateLimit.permitsPerPeriod())
                .limitRefreshPeriod(rateLimit.period())
                .timeoutDuration(Duration.ZERO)
                .build();
        this.registry = RateLimiterRegistry.of(Map.of(CONFIG_NAME, config));
    }

    public boolean tryAcquire(String orgId) {
        return registry.rateLimiter(orgId, CONFIG_NAME).acquirePermission();
    }
}
