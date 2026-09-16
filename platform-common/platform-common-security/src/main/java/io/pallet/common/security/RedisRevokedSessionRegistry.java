package io.pallet.common.security;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Distributed registry backed by Redis, shared by every service instance and every service
 * validating a token. Fails open on a Redis error: a revocation check going dark shouldn't take
 * every authenticated request, platform-wide, down with it — the same tradeoff
 * {@code RedisTokenBucketRateLimiter} makes for edge rate limiting.
 */
public class RedisRevokedSessionRegistry implements RevokedSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RedisRevokedSessionRegistry.class);
    private static final String KEY_PREFIX = "pallet:session:revoked:";
    private static final String UNAVAILABLE_METRIC = "security.session_revocation.unavailable";

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public RedisRevokedSessionRegistry(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    private static String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    @Override
    public void revoke(String sessionId, Duration retention) {
        redisTemplate.opsForValue().set(key(sessionId), "1", retention);
    }

    @Override
    public boolean isRevoked(String sessionId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(sessionId)));
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for session revocation checks; failing open", e);
            meterRegistry.counter(UNAVAILABLE_METRIC).increment();
            return false;
        }
    }
}
