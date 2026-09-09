package io.pallet.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(classes = RedisEventIdempotencyGuardIntegrationTest.TestApp.class)
@Import(RedisTestContainerConfiguration.class)
class RedisEventIdempotencyGuardIntegrationTest {

    @Autowired
    private EventIdempotencyGuard guard;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void firstCallerWinsReleaseReopensAndExpiryFrees() throws InterruptedException {
        assertThat(guard.markProcessed("evt-1", Duration.ofMinutes(1))).isTrue();
        assertThat(guard.markProcessed("evt-1", Duration.ofMinutes(1))).isFalse();

        guard.release("evt-1");
        assertThat(guard.markProcessed("evt-1", Duration.ofMinutes(1))).isTrue();

        assertThat(guard.markProcessed("evt-2", Duration.ofMillis(300))).isTrue();
        Thread.sleep(400);
        assertThat(guard.markProcessed("evt-2", Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void theReservationKeyCarriesATtl() {
        guard.markProcessed("evt-ttl", Duration.ofMinutes(5));

        Long ttl = redisTemplate.getExpire("pallet:idem:evt:evt-ttl");
        assertThat(ttl).isBetween(1L, 300L);
    }

    @Test
    void twoInstancesRacingTheSameEventIdSeeExactlyOneWinner() throws Exception {
        EventIdempotencyGuard secondInstance = new RedisEventIdempotencyGuard(redisTemplate);
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            AtomicInteger winners = new AtomicInteger();
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                EventIdempotencyGuard racer = (i % 2 == 0) ? guard : secondInstance;
                futures[i] = pool.submit(() -> {
                    if (racer.markProcessed("evt-race", Duration.ofMinutes(1))) {
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

    @SpringBootConfiguration
    @ImportAutoConfiguration({DataRedisAutoConfiguration.class, ServiceConnectionAutoConfiguration.class})
    static class TestApp {

        @Bean
        EventIdempotencyGuard eventIdempotencyGuard(StringRedisTemplate redisTemplate) {
            return new RedisEventIdempotencyGuard(redisTemplate);
        }
    }
}
