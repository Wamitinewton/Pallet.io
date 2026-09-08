package io.pallet.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryEventIdempotencyGuardTest {

    private final InMemoryEventIdempotencyGuard guard = new InMemoryEventIdempotencyGuard();

    @Test
    void firstCallerWinsAndTheImmediateSecondLoses() {
        assertThat(guard.markProcessed("evt-1", Duration.ofMinutes(1))).isTrue();
        assertThat(guard.markProcessed("evt-1", Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void releaseReopensTheReservation() {
        guard.markProcessed("evt-2", Duration.ofMinutes(1));
        guard.release("evt-2");
        assertThat(guard.markProcessed("evt-2", Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void anEntryPastItsRetentionCanBeReservedAgain() throws InterruptedException {
        assertThat(guard.markProcessed("evt-3", Duration.ofMillis(50))).isTrue();
        Thread.sleep(80);
        assertThat(guard.markProcessed("evt-3", Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void exactlyOneOf50RacingCallersWins() throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            AtomicInteger winners = new AtomicInteger();
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    if (guard.markProcessed("evt-race", Duration.ofMinutes(1))) {
                        winners.incrementAndGet();
                    }
                });
            }
            for (Future<?> future : futures) {
                future.get();
            }
            assertThat(winners).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
