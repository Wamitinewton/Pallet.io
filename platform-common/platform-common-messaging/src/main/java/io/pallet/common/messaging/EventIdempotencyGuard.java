package io.pallet.common.messaging;

import java.time.Duration;

/**
 * The exactly-once gate a listener checks first: the first line of a
 * {@code @KafkaListener} body is {@code if (!guard.markProcessed(eventId, retention)) return;},
 * and a processing failure calls {@link #release(String)} before letting the exception
 * propagate so Kafka's redelivery is actually retried.
 */
public interface EventIdempotencyGuard {

    /**
     * Exactly one caller racing on {@code eventId} within {@code retention} gets {@code true}; the rest get {@code false}.
     */
    boolean markProcessed(String eventId, Duration retention);

    /**
     * Undo a reservation after processing failed, so a redelivery is reprocessed rather than dropped.
     */
    void release(String eventId);
}
