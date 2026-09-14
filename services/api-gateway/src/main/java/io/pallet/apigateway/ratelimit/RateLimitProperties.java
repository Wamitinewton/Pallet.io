package io.pallet.apigateway.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pallet.gateway.rate-limit")
public record RateLimitProperties(boolean enabled, int capacity, Duration window) {}
