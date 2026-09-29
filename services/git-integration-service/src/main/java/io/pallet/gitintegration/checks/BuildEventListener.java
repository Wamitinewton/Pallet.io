package io.pallet.gitintegration.checks;

import io.pallet.common.events.BuildFailed;
import io.pallet.common.events.BuildStarted;
import io.pallet.common.events.BuildSucceeded;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.EventPayloads;
import io.pallet.common.inbox.TransactionalInbox;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Records the check run each build event asks for and acknowledges; the reporter tells GitHub. */
@Component
class BuildEventListener {

    static final String STARTED_CONSUMER = "check-run-build-started";
    static final String SUCCEEDED_CONSUMER = "check-run-build-succeeded";
    static final String FAILED_CONSUMER = "check-run-build-failed";

    private static final Logger log = LoggerFactory.getLogger(BuildEventListener.class);

    private final JsonMapper jsonMapper;
    private final TransactionalInbox inbox;
    private final DesiredStateWriter writer;
    private final CheckRunMetrics metrics;

    BuildEventListener(
            JsonMapper jsonMapper, TransactionalInbox inbox, DesiredStateWriter writer, CheckRunMetrics metrics) {
        this.jsonMapper = jsonMapper;
        this.inbox = inbox;
        this.writer = writer;
        this.metrics = metrics;
    }

    @KafkaListener(id = "check-run-build-started-listener", idIsGroup = false, topics = Topics.BUILD_STARTED)
    @Transactional
    void onBuildStarted(ConsumerRecord<String, JsonNode> record) {
        metrics.countingFailures(STARTED_CONSUMER, () -> {
            BuildStarted event = EventPayloads.read(jsonMapper, record.value(), BuildStarted.class);
            CheckSubject subject =
                    CheckSubject.of("BuildStarted", event.eventId(), event.orgId(), event.appId(), event.commitSha());
            handle(STARTED_CONSUMER, event.eventType(), subject, writer::buildStarted);
        });
    }

    @KafkaListener(id = "check-run-build-succeeded-listener", idIsGroup = false, topics = Topics.BUILD_SUCCEEDED)
    @Transactional
    void onBuildSucceeded(ConsumerRecord<String, JsonNode> record) {
        metrics.countingFailures(SUCCEEDED_CONSUMER, () -> {
            BuildSucceeded event = EventPayloads.read(jsonMapper, record.value(), BuildSucceeded.class);
            CheckSubject subject =
                    CheckSubject.of("BuildSucceeded", event.eventId(), event.orgId(), event.appId(), event.commitSha());
            handle(SUCCEEDED_CONSUMER, event.eventType(), subject, writer::buildSucceeded);
        });
    }

    @KafkaListener(id = "check-run-build-failed-listener", idIsGroup = false, topics = Topics.BUILD_FAILED)
    @Transactional
    void onBuildFailed(ConsumerRecord<String, JsonNode> record) {
        metrics.countingFailures(FAILED_CONSUMER, () -> {
            BuildFailed event = EventPayloads.read(jsonMapper, record.value(), BuildFailed.class);
            CheckSubject subject =
                    CheckSubject.of("BuildFailed", event.eventId(), event.orgId(), event.appId(), event.commitSha());
            handle(FAILED_CONSUMER, event.eventType(), subject, () -> writer.buildFailed(event.reason()));
        });
    }

    private void handle(String consumer, String eventType, CheckSubject subject, Supplier<DesiredCheck> target) {
        if (!inbox.firstDelivery(consumer, subject.eventId())) {
            return;
        }
        DesiredStateWriter.Result result =
                writer.apply(subject.orgId(), subject.appId(), subject.commitSha(), target.get());
        log.info(
                "Check run desire recorded orgId={} appId={} eventId={} type={} result={}",
                subject.orgId(),
                subject.appId(),
                subject.eventId(),
                eventType,
                result);
    }
}
