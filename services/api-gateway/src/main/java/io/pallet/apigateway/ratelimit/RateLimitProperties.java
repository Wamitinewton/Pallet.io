package io.pallet.apigateway.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Edge token bucket: {@code capacity} is the largest burst a subject can send, {@code window} how
 * long an empty bucket takes to refill, so the sustained rate is {@code capacity / window}.
 */
@ConfigurationProperties("pallet.gateway.rate-limit")
public record RateLimitProperties(boolean enabled, int capacity, Duration window) {

    public RateLimitProperties {
        if (enabled && (capacity < 1 || window == null || !window.isPositive())) {
            throw new IllegalArgumentException(
                    "pallet.gateway.rate-limit needs a positive capacity and window when enabled");
        }
    }
}
