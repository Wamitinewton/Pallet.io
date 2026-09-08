package io.pallet.common.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable record of who did what, emitted by most services and consumed by
 * {@code audit-log-service}. {@code identity-service} is the first producer
 * (login, tenant provisioning); the contract exists now so services emit it
 * from the start. {@code context} carries action-specific detail (ADR-0006).
 */
public record AuditEventRecorded(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String actor,
        String action,
        String resource,
        Map<String, Object> context)
        implements PlatformEvent {

    public static final String TYPE = Topics.AUDIT_EVENT_RECORDED;

    public AuditEventRecorded {
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    public static AuditEventRecorded of(
            String orgId, String actor, String action, String resource, Map<String, Object> context) {
        return new AuditEventRecorded(UUID.randomUUID(), TYPE, orgId, Instant.now(), actor, action, resource, context);
    }
}
