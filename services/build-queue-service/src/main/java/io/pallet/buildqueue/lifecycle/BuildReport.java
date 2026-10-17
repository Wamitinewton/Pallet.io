package io.pallet.buildqueue.lifecycle;

import io.pallet.buildqueue.projection.EventFields;
import io.pallet.common.inbox.MalformedEventException;
import java.util.UUID;
import java.util.regex.Pattern;

/** What a {@code build.*} event from build-service says about one build, checked well formed. */
record BuildReport(Type type, UUID eventId, String orgId, String appId, UUID buildId) {

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    enum Type {
        STARTED("BuildStarted"),
        SUCCEEDED("BuildSucceeded"),
        FAILED("BuildFailed");

        private final String event;

        Type(String event) {
            this.event = event;
        }

        String event() {
            return event;
        }

        boolean isResult() {
            return this != STARTED;
        }
    }

    /** @throws MalformedEventException if a field the transition is keyed on is missing or out of shape */
    static BuildReport of(Type type, UUID eventId, String orgId, String appId, String buildId) {
        String event = type.event();
        EventFields.requireEventId(event, eventId);
        return new BuildReport(
                type,
                eventId,
                EventFields.requireId(event, "orgId", orgId),
                EventFields.requireId(event, "appId", appId),
                requireBuildId(event, buildId));
    }

    /** Build ids are minted here in canonical form, so any other spelling can't name one of them. */
    private static UUID requireBuildId(String event, String buildId) {
        if (buildId == null) {
            throw new MalformedEventException(event + ".buildId is required");
        }
        if (!CANONICAL_UUID.matcher(buildId).matches()) {
            throw new MalformedEventException(event + ".buildId is not a canonical UUID");
        }
        return UUID.fromString(buildId);
    }
}
