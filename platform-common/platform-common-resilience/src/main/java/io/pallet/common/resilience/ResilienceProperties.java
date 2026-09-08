package io.pallet.common.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * Named resilience policies, each with breaker + retry + time-limiter settings, plus a
 * {@code default} applied when a name isn't configured. A service tunes a named policy
 * ({@code smtp}, {@code keycloak-admin}, {@code github}, ...) in its own config.
 */
@ConfigurationProperties("pallet.resilience")
public record ResilienceProperties(
    @DefaultValue Policy defaults,
    @DefaultValue Map<String, Policy> policies) {

    /**
     * The effective policy for {@code name}: an entry in {@link #policies}, else {@link #defaults}.
     */
    public Policy resolve(String name) {
        return policies.getOrDefault(name, defaults);
    }

    public record Policy(
        @DefaultValue CircuitBreaker circuitBreaker,
        @DefaultValue Retry retry,
        @DefaultValue TimeLimiter timeLimiter) {
    }

    public record CircuitBreaker(
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("5") int minimumNumberOfCalls,
        @DefaultValue("30s") Duration waitDurationInOpenState,
        @DefaultValue("3") int permittedCallsInHalfOpenState,
        @DefaultValue("50") float slowCallRateThreshold,
        @DefaultValue("5s") Duration slowCallDurationThreshold) {
    }

    public record Retry(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("500ms") Duration waitDuration,
        @DefaultValue("2.0") double exponentialBackoffMultiplier,
        @DefaultValue("10s") Duration maxWaitDuration) {
    }

    public record TimeLimiter(
        @DefaultValue("6s") Duration timeout,
        @DefaultValue("true") boolean cancelRunningFuture) {
    }
}
