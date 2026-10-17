package io.pallet.buildqueue.intake;

import static org.awaitility.Awaitility.await;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.support.ConsumerGroups;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes pushes onto the real broker and reads back what intake wrote. Every live test context consumes every push,
 * and the inbox lets exactly one of them apply it, so assertions read the shared database rather than one context's
 * state.
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
        this.groupId = listeners.getListenerContainer(PushListener.LISTENER_ID).getGroupId();
    }

    static UUID buildIdOf(GitPushReceived push) {
        return BuildIds.buildId(push.eventId());
    }

    void publish(GitPushReceived... pushes) {
        for (GitPushReceived push : pushes) {
            publisher.publish(push);
        }
    }

    /**
     * Waits until this context's listener has committed every push on the topic. Its inbox claim blocks on any other
     * context's uncommitted claim, so by then whichever context applied a push has committed it.
     */
    void awaitProcessed() {
        consumerGroups.awaitCaughtUp(groupId, Topics.GIT_PUSH_RECEIVED);
    }

    Build awaitBuild(GitPushReceived push, BuildStatus status) {
        return await().atMost(WAIT)
                .until(() -> build(push), found -> found.map(Build::status).equals(Optional.of(status)))
                .orElseThrow();
    }

    Optional<Build> build(GitPushReceived push) {
        return builds.findByOrgIdAndBuildId(push.orgId(), buildIdOf(push));
    }

    int buildCount(String orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM build_queue.builds WHERE org_id = ?", Integer.class, orgId);
    }

    int claims(GitPushReceived push) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM build_queue.processed_events WHERE consumer = ? AND event_id = ?",
                Integer.class,
                PushListener.CONSUMER,
                push.eventId());
    }

    /** The org's outbox rows of one type, in the order they were appended. */
    List<JsonNode> outbox(String orgId, String eventType) {
        return jdbc
                .queryForList(
                        "SELECT payload::text FROM build_queue.outbox_events"
                                + " WHERE org_id = ? AND event_type = ? ORDER BY id",
                        String.class,
                        orgId,
                        eventType)
                .stream()
                .map(jsonMapper::readTree)
                .toList();
    }

    int outboxCount(String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM build_queue.outbox_events WHERE org_id = ?", Integer.class, orgId);
    }

    /** A field's text, or null when it is absent or JSON null. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString();
    }

    JsonNode json(String value) {
        return jsonMapper.readTree(value);
    }

    @Override
    public void close() {
        consumerGroups.close();
    }
}
