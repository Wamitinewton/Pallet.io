package io.pallet.common.messaging;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Distributed guard: {@code SET key 1 NX EX retention} is the atomic reservation,
 * shared across every consumer instance. Active when a service already has
 * {@code spring-data-redis} and a {@link StringRedisTemplate} bean.
 */
public class RedisEventIdempotencyGuard implements EventIdempotencyGuard {

    private static final String KEY_PREFIX = "pallet:idem:evt:";

    private final StringRedisTemplate redisTemplate;

    public RedisEventIdempotencyGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static String key(String eventId) {
        return KEY_PREFIX + eventId;
    }

    @Override
    public boolean markProcessed(String eventId, Duration retention) {
        Boolean reserved = redisTemplate.opsForValue().setIfAbsent(key(eventId), "1", retention);
        return Boolean.TRUE.equals(reserved);
    }

    @Override
    public void release(String eventId) {
        redisTemplate.delete(key(eventId));
    }
}
