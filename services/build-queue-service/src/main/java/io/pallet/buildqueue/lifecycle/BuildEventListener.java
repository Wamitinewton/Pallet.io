package io.pallet.buildqueue.lifecycle;

import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.build.BuildTransitions;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.dispatch.DispatchSignal;
import io.pallet.buildqueue.lifecycle.BuildReport.Type;
import io.pallet.common.events.BuildFailed;
import io.pallet.common.events.BuildStarted;
import io.pallet.common.events.BuildSucceeded;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.EventPayloads;
import io.pallet.common.inbox.TransactionalInbox;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Moves builds forward on build-service's reports. Every report may be late, duplicated, reordered, or about a build
 * this queue never saw, so each is one guarded update; one that matches nothing is accounted for, never failed.
 */
@Component
public class BuildEventListener {

    public static final String STARTED_LISTENER_ID = "build-started-listener";
    public static final String SUCCEEDED_LISTENER_ID = "build-succeeded-listener";
    public static final String FAILED_LISTENER_ID = "build-failed-listener";

    /** The containers that hear build-service: while any of them is down, its silence proves nothing. */
    public static final List<String> LISTENER_IDS =
            List.of(STARTED_LISTENER_ID, SUCCEEDED_LISTENER_ID, FAILED_LISTENER_ID);

    static final String STARTED_CONSUMER = "build-started";
    static final String SUCCEEDED_CONSUMER = "build-succeeded";
    static final String FAILED_CONSUMER = "build-failed";

    private final JsonMapper jsonMapper;
    private final TransactionalInbox inbox;
    private final BuildTransitions transitions;
    private final DispatchSignal dispatchSignal;
    private final LifecycleAnomalies anomalies;
    private final BuildQueueProperties properties;

    BuildEventListener(
            JsonMapper jsonMapper,
            TransactionalInbox inbox,
            BuildTransitions transitions,
            DispatchSignal dispatchSignal,
            LifecycleAnomalies anomalies,
            BuildQueueProperties properties) {
        this.jsonMapper = jsonMapper;
        this.inbox = inbox;
        this.transitions = transitions;
        this.dispatchSignal = dispatchSignal;
        this.anomalies = anomalies;
        this.properties = properties;
    }

    @KafkaListener(id = STARTED_LISTENER_ID, idIsGroup = false, topics = Topics.BUILD_STARTED)
    @Transactional
    void onBuildStarted(ConsumerRecord<String, JsonNode> record) {
        BuildStarted event = EventPayloads.read(jsonMapper, record.value(), BuildStarted.class);
        BuildReport report =
                BuildReport.of(Type.STARTED, event.eventId(), event.orgId(), event.appId(), event.buildId());
        if (!inbox.firstDelivery(STARTED_CONSUMER, report.eventId())) {
            return;
        }
        if (transitions
                .start(report.orgId(), report.appId(), report.buildId(), runLease())
                .isEmpty()) {
            anomalies.unmatched(report);
        }
    }

    @KafkaListener(id = SUCCEEDED_LISTENER_ID, idIsGroup = false, topics = Topics.BUILD_SUCCEEDED)
    @Transactional
    void onBuildSucceeded(ConsumerRecord<String, JsonNode> record) {
        BuildSucceeded event = EventPayloads.read(jsonMapper, record.value(), BuildSucceeded.class);
        BuildReport report =
                BuildReport.of(Type.SUCCEEDED, event.eventId(), event.orgId(), event.appId(), event.buildId());
        if (!inbox.firstDelivery(SUCCEEDED_CONSUMER, report.eventId())) {
            return;
        }
        finished(report, transitions.succeed(report.orgId(), report.appId(), report.buildId()));
    }

    @KafkaListener(id = FAILED_LISTENER_ID, idIsGroup = false, topics = Topics.BUILD_FAILED)
    @Transactional
    void onBuildFailed(ConsumerRecord<String, JsonNode> record) {
        BuildFailed event = EventPayloads.read(jsonMapper, record.value(), BuildFailed.class);
        BuildReport report =
                BuildReport.of(Type.FAILED, event.eventId(), event.orgId(), event.appId(), event.buildId());
        if (!inbox.firstDelivery(FAILED_CONSUMER, report.eventId())) {
            return;
        }
        finished(report, transitions.fail(report.orgId(), report.appId(), report.buildId(), event.reason()));
    }

    /**
     * Wakes the dispatcher only when the build left the in-flight set: an {@code EXPIRED} build's slot was freed when it
     * expired.
     */
    private void finished(BuildReport report, Optional<BuildStatus> left) {
        if (left.isEmpty()) {
            anomalies.unmatched(report);
            return;
        }
        BuildStatus from = left.get();
        if (from.isInFlight()) {
            dispatchSignal.notifyDispatcher();
        } else if (from == BuildStatus.EXPIRED) {
            anomalies.lateResult(report);
        }
    }

    /**
     * Read when the build starts: a configuration change mid-build leases the builds that start afterwards, never the
     * running ones.
     */
    private Duration runLease() {
        return properties.build().maxDuration().plus(properties.lease().grace());
    }
}
