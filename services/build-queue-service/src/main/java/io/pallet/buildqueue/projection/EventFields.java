package io.pallet.buildqueue.projection;

import io.pallet.common.inbox.EventPayloads;
import io.pallet.common.inbox.MalformedEventException;
import java.time.Instant;
import java.util.UUID;

/** Field checks shared by the event listeners; every failure is a {@link MalformedEventException}. */
public final class EventFields {

    public static final int MAX_ID_LENGTH = 64;
    static final int MAX_SLUG_LENGTH = 63;

    private EventFields() {}

    public static void requireEventId(String event, UUID eventId) {
        if (eventId == null) {
            throw new MalformedEventException(event + ".eventId is required");
        }
    }

    static void requireOccurredAt(String event, Instant occurredAt) {
        if (occurredAt == null) {
            throw new MalformedEventException(event + ".occurredAt is required");
        }
    }

    /** Ids are stored verbatim and compared exactly, so surrounding whitespace is malformed rather than trimmed. */
    public static String requireId(String event, String field, String value) {
        EventPayloads.requireText(event, field, value, MAX_ID_LENGTH);
        if (!value.strip().equals(value)) {
            throw new MalformedEventException(event + "." + field + " has surrounding whitespace");
        }
        return value;
    }

    static String requireSlug(String event, String value) {
        EventPayloads.requireText(event, "slug", value, MAX_SLUG_LENGTH);
        return value.strip();
    }
}
