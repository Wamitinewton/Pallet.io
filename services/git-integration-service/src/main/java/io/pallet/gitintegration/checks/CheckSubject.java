package io.pallet.gitintegration.checks;

import io.pallet.common.inbox.EventPayloads;
import io.pallet.common.inbox.MalformedEventException;
import java.util.UUID;
import java.util.regex.Pattern;

/** The app and commit a build or deploy event is about, validated before anything is claimed or stored. */
record CheckSubject(UUID eventId, String orgId, UUID appId, String commitSha) {

    static final int MAX_ORG_ID_LENGTH = 64;

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    /** @throws MalformedEventException for a missing id or org, an app id that isn't a UUID, or a SHA that isn't one */
    static CheckSubject of(String event, UUID eventId, String orgId, String appId, String commitSha) {
        if (eventId == null) {
            throw new MalformedEventException(event + ".eventId is required");
        }
        EventPayloads.requireText(event, "orgId", orgId, MAX_ORG_ID_LENGTH);
        EventPayloads.requireText(event, "appId", appId, MAX_ORG_ID_LENGTH);
        if (commitSha == null || !SHA.matcher(commitSha).matches()) {
            throw new MalformedEventException(event + ".commitSha is not a 40-character lowercase hex SHA");
        }
        return new CheckSubject(eventId, orgId, uuid(event, appId), commitSha);
    }

    private static UUID uuid(String event, String appId) {
        try {
            UUID parsed = UUID.fromString(appId);
            if (!parsed.toString().equals(appId)) {
                throw new MalformedEventException(event + ".appId is not a canonical UUID");
            }
            return parsed;
        } catch (IllegalArgumentException notAUuid) {
            throw new MalformedEventException(event + ".appId is not a UUID", notAUuid);
        }
    }
}
