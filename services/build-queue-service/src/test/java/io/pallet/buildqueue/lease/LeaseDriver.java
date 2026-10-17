package io.pallet.buildqueue.lease;

import static org.awaitility.Awaitility.await;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.support.TopicProbe;
import io.pallet.common.events.BuildSucceeded;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Queues builds through real intake, reads back what the sweeper did, and reads the events it published. Deadlines are
 * database time, so waits for one compare against database {@code now()}, never this JVM's clock.
 */
final class LeaseDriver {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(50);

    private final PlatformEventPublisher publisher;
    private final BuildRepository builds;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final String bootstrapServers;

    LeaseDriver(
            PlatformEventPublisher publisher,
            BuildRepository builds,
            JdbcTemplate jdbc,
            JsonMapper jsonMapper,
            String bootstrapServers) {
        this.publisher = publisher;
        this.builds = builds;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.bootstrapServers = bootstrapServers;
    }

    static UUID buildIdOf(GitPushReceived push) {
        return BuildIds.buildId(push.eventId());
    }

    /** A sweep acts on every org in the shared database, so each test starts from an empty queue and no outage. */
    void quiesce() {
        jdbc.update("DELETE FROM build_queue.builds");
        jdbc.update("UPDATE build_queue.lease_sweeper_state SET unreachable_since = NULL");
    }

    void publish(PlatformEvent... events) {
        for (PlatformEvent event : events) {
            publisher.publish(event);
        }
    }

    BuildSucceeded succeeded(Build build) {
        return BuildSucceeded.of(
                build.orgId(), build.buildId().toString(), build.appId(), build.commitSha(), build.pushEventId());
    }

    Build awaitStatus(GitPushReceived push, BuildStatus status) {
        return await().atMost(WAIT)
                .pollInterval(POLL)
                .until(
                        () -> builds.findByOrgIdAndBuildId(push.orgId(), buildIdOf(push)),
                        found -> found.map(Build::status).equals(Optional.of(status)))
                .orElseThrow();
    }

    Build build(GitPushReceived push) {
        return builds.findByOrgIdAndBuildId(push.orgId(), buildIdOf(push)).orElseThrow();
    }

    Instant databaseNow() {
        return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
    }

    void awaitDatabaseTimePast(Instant instant) {
        await().atMost(WAIT).pollInterval(POLL).until(() -> databaseNow().isAfter(instant));
    }

    Optional<Instant> unreachableSince() {
        return Optional.ofNullable(jdbc.queryForObject(
                        "SELECT unreachable_since FROM build_queue.lease_sweeper_state WHERE id = 1",
                        OffsetDateTime.class))
                .map(OffsetDateTime::toInstant);
    }

    /** Rows written to the outbox for {@code eventId}: the relay publishes each row once. */
    int outboxed(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM build_queue.outbox_events WHERE event_id = ?", Integer.class, eventId);
    }

    /** Waits for {@code expected} events about {@code push}'s build on {@code topic}, in the order published. */
    List<JsonNode> published(String topic, GitPushReceived push, int expected) {
        String buildId = buildIdOf(push).toString();
        try (TopicProbe probe = new TopicProbe(bootstrapServers, topic)) {
            return probe
                    .awaitMatching(
                            push.orgId(),
                            record -> buildId.equals(jsonMapper
                                    .readTree(record.value())
                                    .path("buildId")
                                    .asString()),
                            expected)
                    .stream()
                    .map(record -> jsonMapper.readTree(record.value()))
                    .toList();
        }
    }
}
