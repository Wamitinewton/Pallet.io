package io.pallet.common.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Command-style event: any service publishes it to have a tenant notified.
 * {@code notification-service} owns a template registry keyed by
 * {@code notificationType} and does the rendering and delivery.
 *
 * <p>{@code dedupeKey} is distinct from {@code eventId}: {@code eventId} dedupes
 * a redelivery of the same publish, {@code dedupeKey} lets the consumer collapse
 * logically duplicate requests (two services asking for the same welcome email).
 * It is nullable. {@code variables} is intentionally loose per ADR-0006.
 */
public record NotificationRequested(
    UUID eventId,
    String eventType,
    String orgId,
    Instant occurredAt,
    String notificationType,
    String recipient,
    String channel,
    String dedupeKey,
    Map<String, Object> variables
) implements PlatformEvent {

    public static final String TYPE = Topics.NOTIFICATION_REQUESTED;

    public NotificationRequested {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    public static NotificationRequested of(
        String orgId,
        String notificationType,
        String recipient,
        String channel,
        String dedupeKey,
        Map<String, Object> variables) {
        return new NotificationRequested(
            UUID.randomUUID(), TYPE, orgId, Instant.now(),
            notificationType, recipient, channel, dedupeKey, variables);
    }
}
