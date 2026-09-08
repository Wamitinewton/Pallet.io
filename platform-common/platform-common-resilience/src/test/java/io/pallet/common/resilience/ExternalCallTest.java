package io.pallet.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.error.NotFoundException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExternalCallTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private static ResilienceRegistries registriesFor(ResilienceProperties.Policy policy) {
        return new ResilienceRegistries(new ResilienceProperties(policy, Map.of()));
    }

    // A generous, non-tripping breaker: these tests exercise retry/timeout behaviour, not the breaker.
    private static ResilienceProperties.Policy policy(int maxAttempts, Duration retryWait, Duration timeout) {
        return new ResilienceProperties.Policy(
                new ResilienceProperties.CircuitBreaker(
                        50, 1000, 1000, Duration.ofSeconds(30), 3, 50, Duration.ofSeconds(5)),
                new ResilienceProperties.Retry(maxAttempts, retryWait, 1.0, retryWait),
                new ResilienceProperties.TimeLimiter(timeout, true));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void successfulSupplierIsReturnedWithNoRetryAndRecordsABreakerSuccess() {
        ResilienceRegistries registries = registriesFor(policy(3, Duration.ofMillis(10), Duration.ofSeconds(2)));
        ExternalCall externalCall = new ExternalCallExecutor(registries, executor);
        AtomicInteger invocations = new AtomicInteger();

        String result = externalCall.call("probe", () -> {
            invocations.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(invocations.get()).isEqualTo(1);
        assertThat(registries.circuitBreaker("probe").getMetrics().getNumberOfSuccessfulCalls())
                .isEqualTo(1);
    }

    @Test
    void aSupplierThatFailsTwiceThenSucceedsIsRetriedAndRecordsASuccessfulRetryMetric() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ResilienceRegistries registries = registriesFor(policy(3, Duration.ofMillis(5), Duration.ofSeconds(2)));
        TaggedRetryMetrics.ofRetryRegistry(registries.retryRegistry()).bindTo(meterRegistry);
        ExternalCall externalCall = new ExternalCallExecutor(registries, executor);
        AtomicInteger attempts = new AtomicInteger();

        String result = externalCall.call("probe", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("not yet");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(meterRegistry
                        .get("resilience4j.retry.calls")
                        .tag("kind", "successful_with_retry")
                        .functionCounter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void aSupplierThatAlwaysFailsThrowsExternalServiceExceptionAfterExactlyMaxAttempts() {
        ResilienceRegistries registries = registriesFor(policy(3, Duration.ofMillis(5), Duration.ofSeconds(2)));
        ExternalCall externalCall = new ExternalCallExecutor(registries, executor);
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException failure = new RuntimeException("always fails");

        assertThatThrownBy(() -> externalCall.call("probe", () -> {
                    attempts.incrementAndGet();
                    throw failure;
                }))
                .isInstanceOf(ExternalServiceException.class)
                .hasCause(failure);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void aSupplierThatOutlivesTheTimeoutThrowsExternalServiceExceptionAndCountsAsAnAttempt() {
        ResilienceRegistries registries = registriesFor(policy(2, Duration.ofMillis(5), Duration.ofMillis(100)));
        ExternalCall externalCall = new ExternalCallExecutor(registries, executor);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> externalCall.call("probe", () -> {
                    attempts.incrementAndGet();
                    sleep(Duration.ofMillis(500));
                    return "too late";
                }))
                .isInstanceOf(ExternalServiceException.class)
                .hasCauseInstanceOf(TimeoutException.class);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void anAppExceptionPropagatesAsIsOnTheFirstAttempt() {
        ResilienceRegistries registries = registriesFor(policy(3, Duration.ofMillis(5), Duration.ofSeconds(2)));
        ExternalCall externalCall = new ExternalCallExecutor(registries, executor);
        AtomicInteger attempts = new AtomicInteger();
        NotFoundException notFound = new NotFoundException("widget", "w-1");

        assertThatThrownBy(() -> externalCall.call("probe", () -> {
                    attempts.incrementAndGet();
                    throw notFound;
                }))
                .isSameAs(notFound);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void theFallbackOverloadReturnsTheFallbackValueInsteadOfThrowing() {
        ResilienceRegistries registries = registriesFor(policy(2, Duration.ofMillis(5), Duration.ofSeconds(2)));
        ExternalCall externalCall = new ExternalCallExecutor(registries, executor);
        RuntimeException failure = new RuntimeException("boom");

        String result = externalCall.call(
                "probe",
                () -> {
                    throw failure;
                },
                cause -> "fallback:" + cause.getMessage());

        assertThat(result).isEqualTo("fallback:boom");
    }
}
