package io.pallet.common.outbox;

import java.util.UUID;

record OutboxRow(
        Long id,
        UUID eventId,
        String orgId,
        String eventType,
        String payload,
        boolean sensitive,
        String traceparent,
        int attempts,
        String recordKey,
        boolean tombstone) {

    static OutboxRow pending(
            UUID eventId,
            String orgId,
            String eventType,
            String payload,
            boolean sensitive,
            String traceparent,
            String recordKey) {
        return new OutboxRow(null, eventId, orgId, eventType, payload, sensitive, traceparent, 0, recordKey, false);
    }

    static OutboxRow pendingTombstone(UUID eventId, String orgId, String topic, String traceparent, String recordKey) {
        return new OutboxRow(null, eventId, orgId, topic, null, false, traceparent, 0, recordKey, true);
    }
}
