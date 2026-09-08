package io.pallet.common.resilience;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.error.ExternalServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerBehaviourTest {

    private static final String POLICY = "probe";

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    // No retries: each ExternalCall.call is exactly one call the breaker sees, so a fixed
    // number of failing calls maps directly onto the breaker's failure count.
    private final ResilienceRegistries registries = new ResilienceRegistries(new ResilienceProperties(
            new ResilienceProperties.Policy(
                    new ResilienceProperties.CircuitBreaker(50, 5, 5, Duration.ofMillis(100), 1, 100, Duration.ofSeconds(5)),
                    new ResilienceProperties.Retry(1, Duration.ofMillis(1), 1.0, Duration.ofMillis(1)),
                    new ResilienceProperties.TimeLimiter(Duration.ofSeconds(2), true)),
            Map.of()));

    private final ExternalCall externalCall = new ExternalCallExecutor(registries, executor);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void opensAfterTheFailureThresholdThenHalfOpensAndClosesOnASuccess() throws InterruptedException {
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registries.circuitBreakerRegistry()).bindTo(meterRegistry);
        CircuitBreaker circuitBreaker = registries.circuitBreaker(POLICY);
        List<CircuitBreaker.State> transitions = new CopyOnWriteArrayList<>();
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> transitions.add(event.getStateTransition().getToState()));
        AtomicInteger invocations = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> externalCall.call(POLICY, () -> {
                invocations.incrementAndGet();
                throw new RuntimeException("boom");
            })).isInstanceOf(ExternalServiceException.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(stateGauge(CircuitBreaker.State.OPEN)).isEqualTo(1.0);

        // The breaker is open and the wait duration hasn't elapsed yet: fails fast, no 6th invocation.
        assertThatThrownBy(() -> externalCall.call(POLICY, () -> {
            invocations.incrementAndGet();
            return "unreachable";
        }))
                .isInstanceOf(ExternalServiceException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        assertThat(invocations.get()).isEqualTo(5);

        Thread.sleep(150);
        String result = externalCall.call(POLICY, () -> "recovered");

        assertThat(result).isEqualTo("recovered");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(transitions).containsExactly(
                CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.CLOSED);
        assertThat(stateGauge(CircuitBreaker.State.CLOSED)).isEqualTo(1.0);
        assertThat(stateGauge(CircuitBreaker.State.OPEN)).isEqualTo(0.0);
    }

    private double stateGauge(CircuitBreaker.State state) {
        return meterRegistry.get("resilience4j.circuitbreaker.state")
                .tag("state", state.name().toLowerCase())
                .gauge().value();
    }
}
