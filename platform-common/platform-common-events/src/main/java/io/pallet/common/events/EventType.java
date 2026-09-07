package io.pallet.common.events;

/**
 * Resolves the Kafka topic an event is published to. In Pallet's catalog the
 * topic name equals the event type, so this is the identity function today; it
 * is the single seam to change if a type ever needs a topic distinct from it.
 */
public final class EventType {

    private EventType() {
    }

    public static String topicFor(PlatformEvent event) {
        return event.eventType();
    }
}
