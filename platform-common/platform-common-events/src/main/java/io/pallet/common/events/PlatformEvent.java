package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

public interface PlatformEvent {

    UUID eventId();

    /**
     * Name in {@code domain.fact} form, past tense, e.g. {@code deploy.state.changed}.
     */
    String eventType();

    /**
     * The organization this event belongs to, taken from the {@code org_id} token claim.
     */
    String orgId();

    Instant occurredAt();
}
