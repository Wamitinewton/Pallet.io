package io.pallet.apigateway.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Token bucket per subject key: holds at most {@code capacity} tokens and refills continuously at
 * {@code capacity / window}, so a drained bucket can't burst again at a window boundary. The whole
 * refill-and-take runs in one Lua script against Redis's own clock, keeping concurrent callers and
 * clock-skewed gateway instances from double-spending a token. Fails open on a Redis error: an edge
 * abuse-prevention layer going dark shouldn't take every route down with it.
 */
@Component
class RedisTokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);
    // Distinct from the old fixed-window prefix so a mixed fleet mid-rollout never hits WRONGTYPE
    // on a key the other algorithm wrote.
    private static final String KEY_PREFIX = "gateway:ratelimit:bucket:";
    private static final String UNAVAILABLE_METRIC = "gateway.ratelimit.unavailable";

    // Returns 0 when a token was taken, otherwise the milliseconds until one will be available. A
    // rejection writes nothing: refill is linear and a rejected bucket is below capacity, so
    // recomputing from the stored state later gives the same answer. The key expires once the
    // bucket would be full again, since a full bucket and a missing key are indistinguishable.
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local rate = capacity / tonumber(ARGV[2])
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + tonumber(time[2]) / 1000

            local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'refilled_at')
            local tokens = tonumber(bucket[1])
            local refilled_at = tonumber(bucket[2])
            if tokens == nil or refilled_at == nil then
              tokens = capacity
              refilled_at = now
            end

            tokens = math.min(capacity, tokens + math.max(0, now - refilled_at) * rate)
            if tokens < 1 then
              return math.max(1, math.ceil((1 - tokens) / rate))
            end

            tokens = tokens - 1
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'refilled_at', now)
            redis.call('PEXPIRE', KEYS[1], math.max(1, math.ceil((capacity - tokens) / rate)))
            return 0
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

    Decision tryConsume(String subjectKey) {
        return tryConsume(subjectKey, properties.capacity(), properties.window());
    }

    Decision tryConsume(String subjectKey, int capacity, Duration window) {
        try {
            Long retryAfterMillis = redisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(KEY_PREFIX + subjectKey),
                    String.valueOf(capacity),
                    String.valueOf(window.toMillis()));
            return retryAfterMillis == null || retryAfterMillis == 0
                    ? Decision.ALLOWED
                    : Decision.rejected(Duration.ofMillis(retryAfterMillis));
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for edge rate limiting; failing open", e);
            meterRegistry.counter(UNAVAILABLE_METRIC).increment();
            return Decision.ALLOWED;
        }
    }

    /** Whether a request was admitted and, if not, how long until the bucket holds a token again. */
    record Decision(boolean allowed, Duration retryAfter) {

        static final Decision ALLOWED = new Decision(true, Duration.ZERO);

        static Decision rejected(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }
    }
}
