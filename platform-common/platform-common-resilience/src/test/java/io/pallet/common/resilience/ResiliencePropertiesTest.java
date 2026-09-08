package io.pallet.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ResiliencePropertiesTest {

    private static void assertDefaults(ResilienceProperties properties) {
        ResilienceProperties.CircuitBreaker circuitBreaker =
                properties.defaults().circuitBreaker();
        assertThat(circuitBreaker.failureRateThreshold()).isEqualTo(50f);
        assertThat(circuitBreaker.slidingWindowSize()).isEqualTo(10);
        assertThat(circuitBreaker.minimumNumberOfCalls()).isEqualTo(5);
        assertThat(circuitBreaker.waitDurationInOpenState()).isEqualTo(Duration.ofSeconds(30));
        assertThat(circuitBreaker.permittedCallsInHalfOpenState()).isEqualTo(3);
        assertThat(circuitBreaker.slowCallRateThreshold()).isEqualTo(50f);
        assertThat(circuitBreaker.slowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(5));

        ResilienceProperties.Retry retry = properties.defaults().retry();
        assertThat(retry.maxAttempts()).isEqualTo(3);
        assertThat(retry.waitDuration()).isEqualTo(Duration.ofMillis(500));
        assertThat(retry.exponentialBackoffMultiplier()).isEqualTo(2.0);
        assertThat(retry.maxWaitDuration()).isEqualTo(Duration.ofSeconds(10));

        ResilienceProperties.TimeLimiter timeLimiter = properties.defaults().timeLimiter();
        assertThat(timeLimiter.timeout()).isEqualTo(Duration.ofSeconds(6));
        assertThat(timeLimiter.cancelRunningFuture()).isTrue();

        assertThat(properties.policies()).isEmpty();
    }

    private static ResilienceProperties.Policy defaultPolicy() {
        return new ResilienceProperties.Policy(
                new ResilienceProperties.CircuitBreaker(
                        50, 10, 5, Duration.ofSeconds(30), 3, 50, Duration.ofSeconds(5)),
                new ResilienceProperties.Retry(3, Duration.ofMillis(500), 2.0, Duration.ofSeconds(10)),
                new ResilienceProperties.TimeLimiter(Duration.ofSeconds(6), true));
    }

    @Test
    void defaultValuesBindFromAnEmptySource() {
        new ApplicationContextRunner()
                .withUserConfiguration(EnableResilienceProperties.class)
                .run(context -> assertDefaults(context.getBean(ResilienceProperties.class)));
    }

    @Test
    void resolveReturnsTheNamedPolicyWhenConfigured() {
        ResilienceProperties.Policy smtpPolicy = new ResilienceProperties.Policy(
                new ResilienceProperties.CircuitBreaker(
                        30, 20, 10, Duration.ofSeconds(15), 2, 40, Duration.ofSeconds(2)),
                new ResilienceProperties.Retry(5, Duration.ofMillis(200), 1.5, Duration.ofSeconds(5)),
                new ResilienceProperties.TimeLimiter(Duration.ofSeconds(3), false));
        ResilienceProperties.Policy defaultPolicy = defaultPolicy();
        ResilienceProperties properties = new ResilienceProperties(defaultPolicy, Map.of("smtp", smtpPolicy));

        assertThat(properties.resolve("smtp")).isSameAs(smtpPolicy);
    }

    @Test
    void resolveFallsBackToDefaultsForAnUnknownName() {
        ResilienceProperties.Policy defaultPolicy = defaultPolicy();
        ResilienceProperties properties = new ResilienceProperties(defaultPolicy, Map.of());

        assertThat(properties.resolve("unknown")).isSameAs(defaultPolicy);
    }

    @EnableConfigurationProperties(ResilienceProperties.class)
    private static final class EnableResilienceProperties {}
}
