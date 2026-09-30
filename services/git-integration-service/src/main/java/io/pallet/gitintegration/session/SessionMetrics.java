package io.pallet.gitintegration.session;

import static io.pallet.gitintegration.observability.MetricsCatalog.*;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code git.sessions.active} from a {@code SCAN} of the session keys, never {@code KEYS}. Sessions live at most an
 * hour, so the keyspace it walks stays small; while Redis is unreachable the last count stands.
 */
@Component
public class SessionMetrics {

    private static final Logger log = LoggerFactory.getLogger(SessionMetrics.class);
    private static final long SCAN_BATCH = 1000;

    private final StringRedisTemplate redis;
    private final AtomicLong active = new AtomicLong();

    SessionMetrics(StringRedisTemplate redis, MeterRegistry registry) {
        this.redis = redis;
        Gauge.builder(SESSIONS_ACTIVE, active, AtomicLong::get)
                .description("GitHub user sessions currently held in Redis")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${pallet.git.user-session.metrics-interval:PT1M}")
    public void refresh() {
        ScanOptions sessions = ScanOptions.scanOptions()
                .match(GitHubUserSessionStore.SESSION_KEY_PREFIX + "*")
                .count(SCAN_BATCH)
                .build();
        try (Cursor<String> keys = redis.scan(sessions)) {
            long count = 0;
            while (keys.hasNext()) {
                keys.next();
                count++;
            }
            active.set(count);
        } catch (DataAccessException e) {
            log.warn(
                    "Could not refresh the active session gauge, keeping the last value: {}",
                    e.getClass().getName());
        }
    }
}
