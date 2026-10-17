package io.pallet.buildqueue.lifecycle;

import static org.awaitility.Awaitility.await;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.dispatch.Dispatcher;
import io.pallet.buildqueue.lifecycle.LifecycleAnomalies.Kind;
import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.buildqueue.support.ConsumerGroups;
import io.pallet.buildqueue.support.Counters;
import io.pallet.common.events.BuildFailed;
import io.pallet.common.events.BuildStarted;
import io.pallet.common.events.BuildSucceeded;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * Queues and dispatches builds through real intake and the dispatcher, plays build-service's reports onto the broker,
 * and reads back what the lifecycle listener did.
 *
 * <p>Every live test context consumes every report and the inbox lets exactly one apply it, so assertions read the
 * shared database, and anomaly counts are summed over every context's registry. A context's listener blocks on another
 * context's uncommitted claim, so once this context has caught up on a topic, every report on it has been applied and
 * counted by whichever context claimed it.
 */
final class LifecycleDriver implements AutoCloseable {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private static final Map<String, String> LISTENER_TOPICS = Map.of(
            BuildEventListener.STARTED_LISTENER_ID, Topics.BUILD_STARTED,
            BuildEventListener.SUCCEEDED_LISTENER_ID, Topics.BUILD_SUCCEEDED,
            BuildEventListener.FAILED_LISTENER_ID, Topics.BUILD_FAILED);

    private final PlatformEventPublisher publisher;
    private final BuildRepository builds;
    private final JdbcTemplate jdbc;
    private final Dispatcher dispatcher;
    private final KafkaListenerEndpointRegistry listeners;
    private final ConsumerGroups consumerGroups;

    LifecycleDriver(
            PlatformEventPublisher publisher,
            BuildRepository builds,
            JdbcTemplate jdbc,
            Dispatcher dispatcher,
            KafkaListenerEndpointRegistry listeners,
            String bootstrapServers) {
        this.publisher = publisher;
        this.builds = builds;
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
        this.listeners = listeners;
        this.consumerGroups = new ConsumerGroups(bootstrapServers);
    }

    static UUID buildIdOf(GitPushReceived push) {
        return BuildIds.buildId(push.eventId());
    }

    /** A round serves every org in the shared database, so each test starts from an empty queue. */
    void quiesce() {
        jdbc.update("DELETE FROM build_queue.builds");
    }

    void publish(GitPushReceived... pushes) {
        for (GitPushReceived push : pushes) {
            publisher.publish(push);
        }
    }

    void queue(GitPushReceived... pushes) {
        publish(pushes);
        for (GitPushReceived push : pushes) {
            awaitStatus(push, BuildStatus.QUEUED);
        }
    }

    /** Queues the push and runs one round, which dispatches it: the queue holds nothing else. */
    Build dispatch(GitPushReceived push) {
        queue(push);
        dispatcher.runRound();
        Build build = build(push);
        if (build.status() != BuildStatus.DISPATCHED) {
            throw new IllegalStateException("Round left " + build.buildId() + " " + build.status());
        }
        return build;
    }

    void report(PlatformEvent... reports) {
        for (PlatformEvent report : reports) {
            publisher.publish(report);
        }
    }

    BuildStarted started(Build build) {
        return started(build, build.orgId());
    }

    BuildStarted started(Build build, String orgId) {
        return BuildStarted.of(
                orgId, build.buildId().toString(), build.appId(), build.commitSha(), build.pushEventId());
    }

    BuildSucceeded succeeded(Build build) {
        return BuildSucceeded.of(
                build.orgId(), build.buildId().toString(), build.appId(), build.commitSha(), build.pushEventId());
    }

    BuildFailed failed(Build build, String reason) {
        return BuildFailed.of(
                build.orgId(),
                build.buildId().toString(),
                build.appId(),
                build.commitSha(),
                build.pushEventId(),
                reason);
    }

    /** Expires the build as the lease sweeper would, without waiting out a deadline. */
    void expire(Build build, String reason) {
        jdbc.update(
                "UPDATE build_queue.builds SET status = 'EXPIRED', status_reason = ?, finished_at = now(),"
                        + " version = version + 1 WHERE build_id = ? AND org_id = ?",
                reason,
                build.buildId(),
                build.orgId());
    }

    Build awaitStatus(GitPushReceived push, BuildStatus status) {
        return awaitStatus(push.orgId(), buildIdOf(push), status);
    }

    Build awaitStatus(String orgId, UUID buildId, BuildStatus status) {
        return await().atMost(WAIT)
                .pollInterval(Duration.ofMillis(50))
                .until(
                        () -> builds.findByOrgIdAndBuildId(orgId, buildId),
                        found -> found.map(Build::status).equals(Optional.of(status)))
                .orElseThrow();
    }

    Build build(GitPushReceived push) {
        return build(push.orgId(), buildIdOf(push));
    }

    Build build(String orgId, UUID buildId) {
        return builds.findByOrgIdAndBuildId(orgId, buildId).orElseThrow();
    }

    /** Waits until this context has committed every report currently on the three {@code build.*} topics. */
    void awaitReportsProcessed() {
        LISTENER_TOPICS.forEach((listenerId, topic) -> consumerGroups.awaitCaughtUp(
                listeners.getListenerContainer(listenerId).getGroupId(), topic));
    }

    int claims(String consumer, UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM build_queue.processed_events WHERE consumer = ? AND event_id = ?",
                Integer.class,
                consumer,
                eventId);
    }

    /** Anomalies of {@code kind} counted by every test context in this JVM so far. */
    static double anomalies(Kind kind) {
        return Counters.count(QueueMetrics.EVENTS_ANOMALY, QueueMetrics.TAG_KIND, QueueMetrics.tag(kind.metric()));
    }

    @Override
    public void close() {
        consumerGroups.close();
    }
}
