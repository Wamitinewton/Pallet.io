package io.pallet.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    void allowsABurstUpToCapacityThenRejectsWithTheTimeUntilTheNextToken() {
        String key = uniqueKey();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume(key).allowed()).isTrue();
        }

        RedisTokenBucketRateLimiter.Decision rejected = limiter.tryConsume(key);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isBetween(Duration.ofMillis(1), Duration.ofMillis(400));
    }

    @Test
    void refillsOneTokenAtATimeRatherThanResettingTheWholeBudget() throws InterruptedException {
        String key = uniqueKey();
        drain(key);

        // 5 tokens per 2s is one every 400ms; 500ms buys exactly one.
        Thread.sleep(500);

        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        assertThat(limiter.tryConsume(key).allowed()).isFalse();
    }

    @Test
    void aBucketDrainedJustBeforeAWindowBoundaryCannotBurstAgainJustAfterIt() throws InterruptedException {
        String key = uniqueKey();
        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        Thread.sleep(1900);
        drain(key);

        // A fixed window opened by the first request would reset here and admit 5 more.
        Thread.sleep(200);

        assertThat(acceptedOutOf(key, 5)).isZero();
    }

    @Test
    void idleTimeNeverBanksMoreThanCapacity() throws InterruptedException {
        String key = uniqueKey();
        limiter.tryConsume(key);

        Thread.sleep(1000);

        assertThat(acceptedOutOf(key, 10)).isEqualTo(5);
    }

    @Test
    void theBucketKeyExpiresOnceItWouldBeFullAgain() {
        String key = uniqueKey();

        limiter.tryConsume(key);

        Long ttl = redisTemplate.getExpire("gateway:ratelimit:bucket:" + key, TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(1L, 400L);
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
                    if (limiter.tryConsume(key, 5, Duration.ofHours(1)).allowed()) {
                        accepted.incrementAndGet();
                    }
                });
            }
            for (Future<?> future : futures) {
                future.get();
            }
            // A refill this slow adds nothing during the test, so anything but exactly 5 is either
            // a double-spent token or a lost update.
            assertThat(accepted).hasValue(5);
        } finally {
            pool.shutdownNow();
        }
    }

    private void drain(String key) {
        while (limiter.tryConsume(key).allowed()) {}
    }

    private int acceptedOutOf(String key, int attempts) {
        int accepted = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryConsume(key).allowed()) {
                accepted++;
            }
        }
        return accepted;
    }

    private static String uniqueKey() {
        return "test-" + System.nanoTime();
    }
}
