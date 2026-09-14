package io.pallet.apigateway.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counter, not yet a real token bucket — kept under this name so a later swap to one
 * doesn't ripple through call sites (see {@code ARCHITECTURE.md}'s named tradeoff). One atomic
 * {@code INCR}+conditional-{@code PEXPIRE} Lua script per call so concurrent callers against the
 * same key never race a separate increment/expire pair. Fails open on a Redis error: an edge
 * abuse-prevention layer going dark shouldn't take every route down with it.
 */
@Component
class RedisTokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);
    private static final String KEY_PREFIX = "gateway:ratelimit:";
    private static final String UNAVAILABLE_METRIC = "gateway.ratelimit.unavailable";

    private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;

    RedisTokenBucketRateLimiter(
            StringRedisTemplate redisTemplate, RateLimitProperties properties, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    boolean tryConsume(String subjectKey) {
        try {
            long count = redisTemplate.execute(
                    FIXED_WINDOW_SCRIPT,
                    List.of(KEY_PREFIX + subjectKey),
                    String.valueOf(properties.window().toMillis()));
            return count <= properties.capacity();
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for edge rate limiting; failing open", e);
            meterRegistry.counter(UNAVAILABLE_METRIC).increment();
            return true;
        }
    }
}
