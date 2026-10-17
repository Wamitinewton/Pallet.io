package io.pallet.buildqueue.cancel;

import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.BuildDispatched;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Moves builds straight to a status in the shared database and reads back the outbox. A dispatcher round serves every
 * org, so tests that run one start from an empty queue: {@link #quiesce()} removes every build, whichever test left it.
 */
final class CancelDriver {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    CancelDriver(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    void quiesce() {
        jdbc.update("DELETE FROM build_queue.builds");
    }

    /** Puts a waiting build where it would be after leaving the queue, meeting the table's status checks. */
    void moveTo(UUID buildId, BuildStatus status, String reason) {
        String set =
                switch (status) {
                    case DISPATCHED ->
                        "status = 'DISPATCHED', attempt = 1, dispatched_at = now(),"
                                + " start_deadline = now() + interval '5 minutes'";
                    case SUCCEEDED, SUPERSEDED, CANCELLED ->
                        "status = '" + status + "', status_reason = ?, finished_at = now()";
                    default -> throw new IllegalArgumentException("not a status a test moves a build to: " + status);
                };
        String sql = "UPDATE build_queue.builds SET " + set + ", version = version + 1 WHERE build_id = ?";
        if (status == BuildStatus.DISPATCHED) {
            jdbc.update(sql, buildId);
        } else {
            jdbc.update(sql, reason, buildId);
        }
    }

    BuildStatus status(UUID buildId) {
        return BuildStatus.valueOf(
                jdbc.queryForObject("SELECT status FROM build_queue.builds WHERE build_id = ?", String.class, buildId));
    }

    String statusReason(UUID buildId) {
        return jdbc.queryForObject(
                "SELECT status_reason FROM build_queue.builds WHERE build_id = ?", String.class, buildId);
    }

    String cancelledBy(UUID buildId) {
        return jdbc.queryForObject(
                "SELECT cancelled_by FROM build_queue.builds WHERE build_id = ?", String.class, buildId);
    }

    /** Frees the build's slot without a report round trip through the lifecycle listener. */
    void finish(UUID buildId) {
        jdbc.update(
                "UPDATE build_queue.builds SET status = 'SUCCEEDED', finished_at = now(), version = version + 1"
                        + " WHERE build_id = ? AND status = 'DISPATCHED'",
                buildId);
    }

    List<JsonNode> cancelledEvents(UUID buildId) {
        return outbox(BuildCancelled.TYPE, "payload->>'buildId' = ?", buildId.toString());
    }

    List<JsonNode> dispatchedEvents(UUID buildId) {
        return outbox(BuildDispatched.TYPE, "payload->>'buildId' = ?", buildId.toString());
    }

    List<JsonNode> auditEvents(UUID buildId) {
        return outbox(AuditEventRecorded.TYPE, "payload->>'resource' = ?", "build:" + buildId);
    }

    List<JsonNode> cancelledEventsOfOrg(String orgId) {
        return outbox(BuildCancelled.TYPE, "org_id = ?", orgId);
    }

    private List<JsonNode> outbox(String eventType, String predicate, String value) {
        return jdbc
                .queryForList(
                        "SELECT payload::text FROM build_queue.outbox_events WHERE event_type = ? AND " + predicate
                                + " ORDER BY id",
                        String.class,
                        eventType,
                        value)
                .stream()
                .map(jsonMapper::readTree)
                .toList();
    }

    /** A field's text, or null when it is absent or JSON null. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString();
    }
}
