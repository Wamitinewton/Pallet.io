package io.pallet.common.messaging;

import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.events.EventType;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.events.Topics;

/**
 * The one way a Pallet service publishes a platform event. It resolves the topic
 * from the event type, keys the record by {@code orgId}, stamps the standard
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
}
