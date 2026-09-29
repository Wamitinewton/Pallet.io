package io.pallet.common.messaging;

import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.events.EventType;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.events.Topics;

/**
 * The one way a Pallet service publishes a platform event. It resolves the topic
 * from the event type, keys the record by {@code orgId} unless told otherwise, stamps the standard
 * headers and blocks a bounded time for the broker ack.
 */
public interface PlatformEventPublisher {

    /**
     * Publishes {@code event} to the topic {@link EventType#topicFor(PlatformEvent)} resolves,
     * keyed by {@code event.orgId()}, with the standard headers stamped. Blocks up to
     * {@code pallet.messaging.publish.send-timeout} for the broker ack (the producer is idempotent,
     * {@code acks=all}). Returns on ack.
     *
     * @throws ExternalServiceException if the send fails or times out
     */
    void publish(PlatformEvent event);

    /**
     * Escape hatch: publish to an explicit topic (e.g. a service-local topic not in {@link Topics}).
     */
    void publish(String topic, PlatformEvent event);

    /**
     * Publishes {@code event} to its catalog topic under {@code key} instead of {@code orgId}.
     *
     * @throws IllegalArgumentException if {@code key} is null or blank
     */
    void publish(PlatformEvent event, String key);

    /**
     * Publishes a tombstone (null value) for {@code key}; only valid on a compacted topic.
     *
     * @throws IllegalArgumentException if {@code topic} is not {@link Topics#isCompacted compacted} or
     *     {@code key} is null or blank
     */
    void publishTombstone(String topic, String key);
}
