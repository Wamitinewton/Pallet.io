package io.pallet.orgteam.inbox;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared decoding and field checks for events consumed from Kafka. */
public final class EventPayloads {

    private EventPayloads() {}

    public static <T> T read(JsonMapper jsonMapper, JsonNode payload, Class<T> type) {
        String name = type.getSimpleName();
        if (payload == null || payload.isNull()) {
            throw new MalformedEventException(name + " payload is empty");
        }
        try {
            return jsonMapper.treeToValue(payload, type);
        } catch (JacksonException unreadable) {
            throw new MalformedEventException(name + " payload is unreadable", unreadable);
        }
    }

    public static void requireText(String event, String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new MalformedEventException(event + "." + field + " is required");
        }
        if (value.strip().length() > maxLength) {
            throw new MalformedEventException(event + "." + field + " is too long");
        }
    }

    /** The trimmed display name, or the local part of {@code email} when none was given. */
    public static String displayNameOrLocalPart(String displayName, String email) {
        if (displayName == null || displayName.isBlank()) {
            return email.substring(0, email.indexOf('@'));
        }
        return displayName.strip();
    }
}
