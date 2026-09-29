package io.pallet.common.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class OutboxRepository {

    private final JdbcClient jdbc;
    private final String relayBatch;
    private final String stats;
    private final String insert;
    private final String markPublished;
    private final String recordFailure;
    private final String deletePublished;

    OutboxRepository(JdbcClient jdbc, OutboxProperties properties) {
        this.jdbc = jdbc;
        String outbox = properties.table("outbox_events");
        this.relayBatch = """
                SELECT e.id, e.event_id, e.org_id, e.event_type, e.payload::text AS payload,
                       e.sensitive, e.traceparent, e.attempts, e.record_key, e.tombstone
                FROM {table} e
                WHERE e.status = 'PENDING'
                  AND e.next_attempt_at <= now()
                  AND e.tx_id < pg_snapshot_xmin(pg_current_snapshot())
                  AND NOT EXISTS (
                      SELECT 1 FROM {table} b
                      WHERE b.org_id = e.org_id AND (b.tx_id, b.id) < (e.tx_id, e.id) AND b.status <> 'PUBLISHED'
                        AND (b.status = 'PARKED' OR b.next_attempt_at > now()))
                ORDER BY e.tx_id, e.id
                LIMIT :batchSize
                """.replace("{table}", outbox);
        this.stats = """
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'PARKED') AS parked,
                       COALESCE(EXTRACT(EPOCH FROM now() - min(created_at) FILTER (WHERE status = 'PENDING')), 0)
                           AS oldest_pending_age_seconds,
                       count(*) FILTER (WHERE status = 'PENDING' AND tx_id >= pg_snapshot_xmin(pg_current_snapshot()))
                           AS held_back
                FROM {table}
                WHERE status <> 'PUBLISHED'
                """.replace("{table}", outbox);
        this.insert = """
                INSERT INTO {table}
                    (event_id, org_id, event_type, payload, sensitive, traceparent, record_key, tombstone)
                VALUES (:eventId, :orgId, :eventType, CAST(:payload AS jsonb), :sensitive, :traceparent,
                        :recordKey, :tombstone)
                """.replace("{table}", outbox);
        this.markPublished = """
                UPDATE {table}
                SET status = 'PUBLISHED',
                    published_at = now(),
                    last_error = NULL,
                    payload = CASE WHEN sensitive THEN NULL ELSE payload END
                WHERE id = :id
                """.replace("{table}", outbox);
        this.recordFailure = """
                UPDATE {table}
                SET attempts = attempts + 1,
                    last_error = :error,
                    next_attempt_at = now() + make_interval(secs => :delay),
                    status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'PARKED' ELSE status END
                WHERE id = :id
                RETURNING status = 'PARKED'
                """.replace("{table}", outbox);
        this.deletePublished = """
                DELETE FROM {table}
                WHERE id IN (
                    SELECT id FROM {table}
                    WHERE status = 'PUBLISHED'
                      AND published_at < now() - make_interval(secs => :windowSeconds)
                    ORDER BY published_at
                    LIMIT :batchSize)
                """.replace("{table}", outbox);
    }

    void insert(OutboxRow row) {
        jdbc.sql(insert)
                .param("eventId", row.eventId())
                .param("orgId", row.orgId())
                .param("eventType", row.eventType())
                .param("payload", row.payload())
                .param("sensitive", row.sensitive())
                .param("traceparent", row.traceparent())
                .param("recordKey", row.recordKey())
                .param("tombstone", row.tombstone())
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

    List<OutboxRow> findRelayBatch(int batchSize) {
        return jdbc.sql(relayBatch)
                .param("batchSize", batchSize)
                .query((rs, rowNum) -> new OutboxRow(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("org_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getBoolean("sensitive"),
                        rs.getString("traceparent"),
                        rs.getInt("attempts"),
                        rs.getString("record_key"),
                        rs.getBoolean("tombstone")))
                .list();
    }

    OutboxStats stats() {
        return jdbc.sql(stats)
                .query((rs, rowNum) -> new OutboxStats(
                        rs.getLong("pending"),
                        rs.getLong("parked"),
                        rs.getDouble("oldest_pending_age_seconds"),
                        rs.getLong("held_back")))
                .single();
    }

    void markPublished(long id) {
        jdbc.sql(markPublished).param("id", id).update();
    }

    /** @return true if this failure parked the row */
    boolean recordFailure(long id, int maxAttempts, String error, double retryDelaySeconds) {
        return jdbc.sql(recordFailure)
                .param("id", id)
                .param("error", error)
                .param("delay", retryDelaySeconds)
                .param("maxAttempts", maxAttempts)
                .query(Boolean.class)
                .single();
    }

    /**
     * Deletes up to {@code batchSize} rows published longer than {@code window} ago, oldest first. Pending and parked
     * rows are never touched.
     *
     * @return the number of rows deleted
     */
    public int deletePublishedOlderThan(Duration window, int batchSize) {
        return jdbc.sql(deletePublished)
                .param("windowSeconds", window.toMillis() / 1000.0)
                .param("batchSize", batchSize)
                .update();
    }
}
