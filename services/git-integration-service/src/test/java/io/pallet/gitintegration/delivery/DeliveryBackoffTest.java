package io.pallet.gitintegration.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

@UnitTest
class DeliveryBackoffTest {

    private static final Duration BASE = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofMinutes(10);

    /** {@code nextDouble()} just under 1: every delay is the top of its range. */
    private static final RandomGenerator HIGHEST = () -> -1L;

    private static final RandomGenerator LOWEST = () -> 0L;

    @Test
    void theCeilingDoublesWithEachAttempt() {
        DeliveryBackoff backoff = new DeliveryBackoff(BASE, MAX, HIGHEST);

        assertThat(backoff.next(1)).isEqualTo(Duration.ofMillis(999));
        assertThat(backoff.next(2)).isEqualTo(Duration.ofMillis(1999));
        assertThat(backoff.next(3)).isEqualTo(Duration.ofMillis(3999));
        assertThat(backoff.next(8)).isEqualTo(Duration.ofMillis(127_999));
    }

    @Test
    void theCeilingStopsAtTheMaximum() {
        DeliveryBackoff backoff = new DeliveryBackoff(BASE, MAX, HIGHEST);

        assertThat(backoff.next(10)).isEqualTo(Duration.ofMillis(511_999));
        assertThat(backoff.next(11)).isEqualTo(MAX.minusMillis(1));
        assertThat(backoff.next(Integer.MAX_VALUE)).isEqualTo(MAX.minusMillis(1));
    }

    @Test
    void anExplicitCapReplacesTheMaximum() {
        DeliveryBackoff backoff = new DeliveryBackoff(BASE, MAX, HIGHEST);

        assertThat(backoff.next(20, Duration.ofMinutes(5)))
                .isEqualTo(Duration.ofMinutes(5).minusMillis(1));
    }

    @Test
    void fullJitterCanReachZero() {
        DeliveryBackoff backoff = new DeliveryBackoff(BASE, MAX, LOWEST);

        assertThat(backoff.next(5)).isZero();
    }

    @Test
    void everyDrawStaysWithinItsCeiling() {
        DeliveryBackoff backoff = new DeliveryBackoff(BASE, MAX, RandomGenerator.of("L64X128MixRandom"));

        for (int attempts = 1; attempts <= 15; attempts++) {
            Duration ceiling = DeliveryBackoff.ceiling(BASE, attempts, MAX);
            for (int draw = 0; draw < 200; draw++) {
                assertThat(backoff.next(attempts)).isBetween(Duration.ZERO, ceiling);
            }
        }
    }

    @Test
    void jitterIsDrawnBelowItsBound() {
        DeliveryBackoff backoff = new DeliveryBackoff(BASE, MAX, HIGHEST);

        assertThat(backoff.jitter(Duration.ofSeconds(5))).isEqualTo(Duration.ofMillis(4999));
    }
}
