package io.pallet.common.inbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.outbox.OutboxProperties;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Effectively-once consumption: a listener claims the event id as the first statement of its transaction, so a
 * redelivery finds the claim and its effects together or neither.
 */
public class TransactionalInbox {

    public static final String DUPLICATES = "inbox.duplicates";
    public static final String TAG_LISTENER = "listener";

    private final JdbcClient jdbc;
    private final MeterRegistry registry;
    private final String duplicatesMetric;
    private final String claim;
    private final String deleteProcessed;

    public TransactionalInbox(JdbcClient jdbc, MeterRegistry registry, OutboxProperties properties) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.duplicatesMetric = properties.metricsPrefix() + "." + DUPLICATES;
        String processed = properties.schema() + ".processed_events";
        this.claim = """
                INSERT INTO {table} (event_id, consumer)
                VALUES (:eventId, :consumer)
                ON CONFLICT DO NOTHING
                """.replace("{table}", processed);
        this.deleteProcessed = """
                DELETE FROM {table}
                WHERE ctid IN (
                    SELECT ctid FROM {table}
                    WHERE processed_at < now() - make_interval(secs => :windowSeconds)
                    ORDER BY processed_at
                    LIMIT :batchSize)
                """.replace("{table}", processed);
    }

    /** @return true only for the first delivery of {@code eventId} to {@code consumer} */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(String consumer, UUID eventId) {
        int inserted = jdbc.sql(claim)
                .param("eventId", eventId)
                .param("consumer", consumer)
                .update();
        if (inserted == 0) {
            registry.counter(duplicatesMetric, TAG_LISTENER, consumer).increment();
        }
        return inserted == 1;
    }

    /**
     * Deletes up to {@code batchSize} claims older than {@code window}, oldest first.
     *
     * @return the number of rows deleted
     */
    public int deleteProcessedOlderThan(Duration window, int batchSize) {
        return jdbc.sql(deleteProcessed)
                .param("windowSeconds", window.toMillis() / 1000.0)
                .param("batchSize", batchSize)
                .update();
    }
}
