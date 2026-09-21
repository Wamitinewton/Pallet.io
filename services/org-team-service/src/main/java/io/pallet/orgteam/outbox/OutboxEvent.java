package io.pallet.orgteam.outbox;

import java.util.UUID;

record OutboxEvent(
        Long id,
        UUID eventId,
        String orgId,
        String eventType,
        String payload,
        boolean sensitive,
        String traceparent,
        int attempts) {

    static OutboxEvent pending(
            UUID eventId, String orgId, String eventType, String payload, boolean sensitive, String traceparent) {
        return new OutboxEvent(null, eventId, orgId, eventType, payload, sensitive, traceparent, 0);
    }
}
