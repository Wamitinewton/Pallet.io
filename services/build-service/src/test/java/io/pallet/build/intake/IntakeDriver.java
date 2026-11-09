package io.pallet.build.intake;

import static org.awaitility.Awaitility.await;

import io.pallet.build.domain.Build;
import io.pallet.build.domain.BuildRepository;
import io.pallet.build.domain.BuildStatus;
import io.pallet.build.support.ConsumerGroups;
import io.pallet.build.support.Counters;
import io.pallet.common.events.BuildDispatched;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes dispatches onto the real broker and reads back what intake wrote. Every live test context consumes every
 * dispatch and the inbox lets exactly one of them apply it, so assertions read the shared database and counters
 * summed over every context.
 */
final class IntakeDriver implements AutoCloseable {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private final PlatformEventPublisher publisher;
    private final BuildRepository builds;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final ConsumerGroups consumerGroups;
    private final String groupId;

    IntakeDriver(
            PlatformEventPublisher publisher,
            BuildRepository builds,
            JdbcTemplate jdbc,
            JsonMapper jsonMapper,
            KafkaListenerEndpointRegistry listeners,
            String bootstrapServers) {
        this.publisher = publisher;
        this.builds = builds;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.consumerGroups = new ConsumerGroups(bootstrapServers);
        this.groupId =
                listeners.getListenerContainer(DispatchListener.LISTENER_ID).getGroupId();
    }

    String groupId() {
        return groupId;
    }

    void publish(BuildDispatched... dispatches) {
        for (BuildDispatched dispatch : dispatches) {
            publisher.publish(dispatch);
        }
    }

    /**
     * Waits until this context's listener has committed every dispatch on the topic. Its inbox claim blocks on any
     * other context's uncommitted claim, so by then whichever context applied a dispatch has committed it.
     */
    void awaitProcessed() {
        consumerGroups.awaitCaughtUp(groupId, Topics.BUILD_DISPATCHED);
    }

    Build awaitStatus(BuildDispatched dispatch, BuildStatus status) {
        return await().atMost(WAIT)
                .until(() -> find(dispatch), found -> found.map(Build::status).equals(Optional.of(status)))
                .orElseThrow();
    }

    Build awaitAttempt(BuildDispatched dispatch, int attempt) {
        return await().atMost(WAIT)
                .until(() -> find(dispatch), found -> found.map(Build::attempt).equals(Optional.of(attempt)))
                .orElseThrow();
    }

    Optional<Build> find(BuildDispatched dispatch) {
        return builds.findByOrgIdAndBuildId(dispatch.orgId(), dispatch.buildId());
    }

    Build build(BuildDispatched dispatch) {
        return find(dispatch).orElseThrow();
    }

    int rowCount(String buildId) {
        return jdbc.queryForObject("SELECT count(*) FROM build.builds WHERE build_id = ?", Integer.class, buildId);
    }

    int claims(BuildDispatched dispatch) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM build.processed_events WHERE consumer = ? AND event_id = ?",
                Integer.class,
                DispatchListener.CONSUMER,
                dispatch.eventId());
    }

    /** The org's outbox rows of one type, in the order they were appended. */
    List<JsonNode> outbox(String orgId, String eventType) {
        return jdbc
                .queryForList(
                        "SELECT payload::text FROM build.outbox_events WHERE org_id = ? AND event_type = ? ORDER BY id",
                        String.class,
                        orgId,
                        eventType)
                .stream()
                .map(jsonMapper::readTree)
                .toList();
    }

    int outboxCount(String orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM build.outbox_events WHERE org_id = ?", Integer.class, orgId);
    }

    void moveToStarting(BuildDispatched dispatch) {
        jdbc.update(
                "UPDATE build.builds SET status = 'STARTING', started_at = now() WHERE build_id = ?",
                dispatch.buildId());
    }

    static double dispatches(IntakeMetrics.Outcome outcome) {
        return Counters.count(IntakeMetrics.DISPATCHES, IntakeMetrics.TAG_OUTCOME, outcome.tag());
    }

    static void awaitDispatches(IntakeMetrics.Outcome outcome, double expected) {
        await().atMost(WAIT).until(() -> dispatches(outcome) >= expected);
    }

    @Override
    public void close() {
        consumerGroups.close();
    }
}
