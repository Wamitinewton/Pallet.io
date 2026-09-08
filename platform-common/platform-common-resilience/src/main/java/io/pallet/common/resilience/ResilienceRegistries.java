package io.pallet.common.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.pallet.common.error.AppException;

/**
 * The three Resilience4j registries, seeded from {@link ResilienceProperties#defaults()}. A named
 * breaker/retry/time-limiter is built lazily from {@link ResilienceProperties#resolve(String)} the
 * first time its name is requested, then reused for every later call under that name.
 */
public final class ResilienceRegistries {

    private final ResilienceProperties properties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;

    public ResilienceRegistries(ResilienceProperties properties) {
        this.properties = properties;
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(toCircuitBreakerConfig(properties.defaults().circuitBreaker()));
        this.retryRegistry = RetryRegistry.of(toRetryConfig(properties.defaults().retry()));
        this.timeLimiterRegistry = TimeLimiterRegistry.of(toTimeLimiterConfig(properties.defaults().timeLimiter()));
    }

    private static CircuitBreakerConfig toCircuitBreakerConfig(ResilienceProperties.CircuitBreaker properties) {
        return CircuitBreakerConfig.custom()
            .failureRateThreshold(properties.failureRateThreshold())
            .slidingWindowSize(properties.slidingWindowSize())
            .minimumNumberOfCalls(properties.minimumNumberOfCalls())
            .waitDurationInOpenState(properties.waitDurationInOpenState())
            .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
            .slowCallRateThreshold(properties.slowCallRateThreshold())
            .slowCallDurationThreshold(properties.slowCallDurationThreshold())
            .ignoreExceptions(AppException.class, IllegalArgumentException.class)
            .build();
    }

    // AppException/IllegalArgumentException are deterministic client-side failures: retrying them
    // wastes an attempt and a breaker slot on something a fresh attempt can never fix.
    private static RetryConfig toRetryConfig(ResilienceProperties.Retry properties) {
        return RetryConfig.custom()
            .maxAttempts(properties.maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialBackoff(
                properties.waitDuration(), properties.exponentialBackoffMultiplier(), properties.maxWaitDuration()))
            .ignoreExceptions(AppException.class, IllegalArgumentException.class)
            .build();
    }

    private static TimeLimiterConfig toTimeLimiterConfig(ResilienceProperties.TimeLimiter properties) {
        return TimeLimiterConfig.custom()
            .timeoutDuration(properties.timeout())
            .cancelRunningFuture(properties.cancelRunningFuture())
            .build();
    }

    public CircuitBreaker circuitBreaker(String policy) {
        return circuitBreakerRegistry.circuitBreaker(policy,
            () -> toCircuitBreakerConfig(properties.resolve(policy).circuitBreaker()));
    }

    public Retry retry(String policy) {
        return retryRegistry.retry(policy, () -> toRetryConfig(properties.resolve(policy).retry()));
    }

    public TimeLimiter timeLimiter(String policy) {
        return timeLimiterRegistry.timeLimiter(policy,
            () -> toTimeLimiterConfig(properties.resolve(policy).timeLimiter()));
    }

    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return circuitBreakerRegistry;
    }

    public RetryRegistry retryRegistry() {
        return retryRegistry;
    }

    public TimeLimiterRegistry timeLimiterRegistry() {
        return timeLimiterRegistry;
    }
}
