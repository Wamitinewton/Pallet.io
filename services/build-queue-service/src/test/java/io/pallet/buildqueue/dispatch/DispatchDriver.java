package io.pallet.buildqueue.dispatch;

import static org.awaitility.Awaitility.await;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.common.events.BuildDispatched;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Queues builds through real intake and reads back what the dispatcher did. A round serves every org in the shared
 * database, so each test starts from an empty queue: {@link #quiesce()} removes every build, whichever test left it.
 */
final class DispatchDriver {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private final PlatformEventPublisher publisher;
    private final BuildRepository builds;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    DispatchDriver(PlatformEventPublisher publisher, BuildRepository builds, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.publisher = publisher;
        this.builds = builds;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    static UUID buildIdOf(GitPushReceived push) {
        return BuildIds.buildId(push.eventId());
    }

    void quiesce() {
        jdbc.update("DELETE FROM build_queue.builds");
    }

    void publish(GitPushReceived... pushes) {
        for (GitPushReceived push : pushes) {
            publisher.publish(push);
        }
    }

    /** Publishes in order and waits until intake has queued every one. */
    void queue(GitPushReceived... pushes) {
        publish(pushes);
        for (GitPushReceived push : pushes) {
            awaitStatus(push, BuildStatus.QUEUED, WAIT);
        }
    }

    Build awaitStatus(GitPushReceived push, BuildStatus status, Duration atMost) {
        return await().atMost(atMost)
                .pollInterval(Duration.ofMillis(50))
                .until(
                        () -> builds.findByOrgIdAndBuildId(push.orgId(), buildIdOf(push)),
                        found -> found.map(Build::status).equals(Optional.of(status)))
                .orElseThrow();
    }

    Build build(GitPushReceived push) {
        return builds.findByOrgIdAndBuildId(push.orgId(), buildIdOf(push)).orElseThrow();
    }

    BuildStatus status(GitPushReceived push) {
        return build(push).status();
    }

    /** Every {@code DISPATCHED} build, by build id. */
    Map<UUID, String> dispatched() {
        return jdbc.queryForList("SELECT build_id, org_id FROM build_queue.builds WHERE status = 'DISPATCHED'").stream()
                .collect(Collectors.toMap(row -> (UUID) row.get("build_id"), row -> (String) row.get("org_id")));
    }

    List<UUID> dispatchedOf(String orgId) {
        return jdbc.queryForList(
                "SELECT build_id FROM build_queue.builds WHERE org_id = ? AND status = 'DISPATCHED' ORDER BY build_id",
                UUID.class,
                orgId);
    }

    /** Frees a slot directly, without a report round trip through the lifecycle listener. */
    void finish(UUID buildId) {
        jdbc.update(
                "UPDATE build_queue.builds SET status = 'SUCCEEDED', finished_at = now(), version = version + 1"
                        + " WHERE build_id = ? AND status = 'DISPATCHED'",
                buildId);
    }

    int finishAllDispatched() {
        return jdbc.update(
                "UPDATE build_queue.builds SET status = 'SUCCEEDED', finished_at = now(), version = version + 1"
                        + " WHERE status = 'DISPATCHED'");
    }

    void forgetTurn(String orgId) {
        jdbc.update("UPDATE build_queue.orgs SET last_served_at = NULL WHERE org_id = ?", orgId);
    }

    Optional<Instant> lastServedAt(String orgId) {
        return Optional.ofNullable(jdbc.queryForObject(
                        "SELECT last_served_at FROM build_queue.orgs WHERE org_id = ?", OffsetDateTime.class, orgId))
                .map(OffsetDateTime::toInstant);
    }

    void deleteOrg(String orgId) {
        jdbc.update(
                "UPDATE build_queue.orgs SET status = 'DELETED', deleted_at = now(), updated_at = now()"
                        + " WHERE org_id = ?",
                orgId);
    }

    /** The org's {@code build.dispatched} outbox payloads, in append order. */
    List<JsonNode> dispatchedEvents(String orgId) {
        return jdbc
                .queryForList(
                        "SELECT payload::text FROM build_queue.outbox_events"
                                + " WHERE org_id = ? AND event_type = ? ORDER BY id",
                        String.class,
                        orgId,
                        BuildDispatched.TYPE)
                .stream()
                .map(jsonMapper::readTree)
                .toList();
    }

    JsonNode json(String value) {
        return jsonMapper.readTree(value);
    }

    /** A field's text, or null when it is absent or JSON null. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString();
    }
}
