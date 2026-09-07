package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Common envelope fields carried by every event on the Pallet backbone.
 *
 * <p>Concrete events are flat records that implement this interface, so the
 * envelope fields and the event-specific fields serialize side by side. That
 * matches the on-the-wire sample in {@code PROJECT.md}. Event payloads are
 * plain JSON for now; a schema registry (Avro or Protobuf) is a later decision
 * recorded in {@code docs/adr/0006-event-schema-format.md}.
 */
public interface PlatformEvent {

    UUID eventId();

    /** Name in {@code domain.fact} form, past tense, e.g. {@code deploy.state.changed}. */
    String eventType();

    /** The organization this event belongs to, taken from the {@code org_id} token claim. */
    String orgId();

    Instant occurredAt();
}
