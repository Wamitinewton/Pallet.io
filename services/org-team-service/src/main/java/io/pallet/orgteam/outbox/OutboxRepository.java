package io.pallet.orgteam.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OutboxRepository {

    private static final String RELAY_BATCH = """
            SELECT e.id, e.event_id, e.org_id, e.event_type, e.payload::text AS payload,
                   e.sensitive, e.traceparent, e.attempts
            FROM org_team.outbox_events e
            WHERE e.status = 'PENDING'
              AND e.next_attempt_at <= now()
              AND e.tx_id < pg_snapshot_xmin(pg_current_snapshot())
              AND NOT EXISTS (
                  SELECT 1 FROM org_team.outbox_events b
                  WHERE b.org_id = e.org_id AND (b.tx_id, b.id) < (e.tx_id, e.id) AND b.status <> 'PUBLISHED'
                    AND (b.status = 'PARKED' OR b.next_attempt_at > now()))
            ORDER BY e.tx_id, e.id
            LIMIT :batchSize
            """;

    private static final String STATS = """
            SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                   count(*) FILTER (WHERE status = 'PARKED') AS parked,
                   COALESCE(EXTRACT(EPOCH FROM now() - min(created_at) FILTER (WHERE status = 'PENDING')), 0)
                       AS oldest_pending_age_seconds,
                   count(*) FILTER (WHERE status = 'PENDING' AND tx_id >= pg_snapshot_xmin(pg_current_snapshot()))
                       AS held_back
            FROM org_team.outbox_events
            WHERE status <> 'PUBLISHED'
            """;

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(OutboxEvent event) {
        jdbc.sql("""
                        INSERT INTO org_team.outbox_events
                            (event_id, org_id, event_type, payload, sensitive, traceparent)
                        VALUES (:eventId, :orgId, :eventType, CAST(:payload AS jsonb), :sensitive, :traceparent)
                        """)
                .param("eventId", event.eventId())
                .param("orgId", event.orgId())
                .param("eventType", event.eventType())
                .param("payload", event.payload())
                .param("sensitive", event.sensitive())
                .param("traceparent", event.traceparent())
                .update();
    }

    boolean tryAcquireRelayLock(long key) {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", key)
                .query(Boolean.class)
                .single();
    }

    /** Bounds how long this transaction waits on any lock, so a schema change cannot freeze the relay. */
    void applyLockTimeout(Duration timeout) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", timeout.toMillis() + "ms")
                .query(String.class)
                .single();
    }

    List<OutboxEvent> findRelayBatch(int batchSize) {
        return jdbc.sql(RELAY_BATCH)
                .param("batchSize", batchSize)
                .query((rs, rowNum) -> new OutboxEvent(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("org_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getBoolean("sensitive"),
                        rs.getString("traceparent"),
                        rs.getInt("attempts")))
                .list();
    }

    OutboxStats stats() {
        return jdbc.sql(STATS)
                .query((rs, rowNum) -> new OutboxStats(
                        rs.getLong("pending"),
                        rs.getLong("parked"),
                        rs.getDouble("oldest_pending_age_seconds"),
                        rs.getLong("held_back")))
                .single();
    }

    void markPublished(long id) {
        jdbc.sql("""
                        UPDATE org_team.outbox_events
                        SET status = 'PUBLISHED',
                            published_at = now(),
                            last_error = NULL,
                            payload = CASE WHEN sensitive THEN NULL ELSE payload END
                        WHERE id = :id
                        """).param("id", id).update();
    }

    /** @return true if this failure parked the row */
    boolean recordFailure(long id, int maxAttempts, String error, double retryDelaySeconds) {
        return jdbc.sql("""
                        UPDATE org_team.outbox_events
                        SET attempts = attempts + 1,
                            last_error = :error,
                            next_attempt_at = now() + make_interval(secs => :delay),
                            status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'PARKED' ELSE status END
                        WHERE id = :id
                        RETURNING status = 'PARKED'
                        """)
                .param("id", id)
                .param("error", error)
                .param("delay", retryDelaySeconds)
                .param("maxAttempts", maxAttempts)
                .query(Boolean.class)
                .single();
    }
}
