package io.pallet.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestContainerConfiguration.class)
@Tag("integration")
class RedisTokenBucketRateLimiterIntegrationTest {

    @Autowired
    private RedisTokenBucketRateLimiter limiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void rateLimitConfig(DynamicPropertyRegistry registry) {
        registry.add("pallet.gateway.rate-limit.enabled", () -> "true");
        registry.add("pallet.gateway.rate-limit.capacity", () -> "5");
        registry.add("pallet.gateway.rate-limit.window", () -> "2s");
    }

    @Test
    void allowsUpToCapacityRequestsWithinTheWindowThenRejects() {
        String key = uniqueKey();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume(key)).isTrue();
        }

        assertThat(limiter.tryConsume(key)).isFalse();
    }

    @Test
    void resetsOnceTheWindowElapses() throws InterruptedException {
        String key = uniqueKey();
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume(key);
        }
        assertThat(limiter.tryConsume(key)).isFalse();

        Thread.sleep(2100);

        assertThat(limiter.tryConsume(key)).isTrue();
    }

    @Test
    void theCounterKeyCarriesATtl() {
        String key = uniqueKey();

        limiter.tryConsume(key);

        Long ttl = redisTemplate.getExpire("gateway:ratelimit:" + key);
        assertThat(ttl).isBetween(1L, 2L);
    }

    @Test
    void concurrentRequestsAgainstTheSameKeyNeverAcceptMoreThanCapacity() throws Exception {
        String key = uniqueKey();
        int attempts = 50;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            AtomicInteger accepted = new AtomicInteger();
            Future<?>[] futures = new Future<?>[attempts];
            for (int i = 0; i < attempts; i++) {
                futures[i] = pool.submit(() -> {
                    if (limiter.tryConsume(key)) {
                        accepted.incrementAndGet();
                    }
                });
            }
            for (Future<?> future : futures) {
                future.get();
            }
            assertThat(accepted).hasValueLessThanOrEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String uniqueKey() {
        return "test-" + System.nanoTime();
    }
}
