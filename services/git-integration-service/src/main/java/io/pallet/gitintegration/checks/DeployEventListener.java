package io.pallet.gitintegration.checks;

import io.pallet.common.events.DeployStateChanged;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.EventPayloads;
import io.pallet.common.inbox.MalformedEventException;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.gitintegration.checks.CheckRunMetrics.DropReason;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records the check run a deploy state change asks for. A change without {@code appId} and {@code commitSha} comes from
 * a producer that predates them and names no check run; it is counted and acknowledged, not dead-lettered.
 */
@Component
class DeployEventListener {

    static final String CONSUMER = "check-run-deploy-state";

    private static final Pattern STATE = Pattern.compile("[A-Z][A-Z_]{0,31}");

    private static final Logger log = LoggerFactory.getLogger(DeployEventListener.class);

    private final JsonMapper jsonMapper;
    private final TransactionalInbox inbox;
    private final DesiredStateWriter writer;
    private final CheckRunMetrics metrics;

    DeployEventListener(
            JsonMapper jsonMapper, TransactionalInbox inbox, DesiredStateWriter writer, CheckRunMetrics metrics) {
        this.jsonMapper = jsonMapper;
        this.inbox = inbox;
        this.writer = writer;
        this.metrics = metrics;
    }

    @KafkaListener(id = "check-run-deploy-state-listener", idIsGroup = false, topics = Topics.DEPLOY_STATE_CHANGED)
    @Transactional
    void onDeployStateChanged(ConsumerRecord<String, JsonNode> record) {
        metrics.countingFailures(CONSUMER, () -> handle(record));
    }

    private void handle(ConsumerRecord<String, JsonNode> record) {
        DeployStateChanged event = EventPayloads.read(jsonMapper, record.value(), DeployStateChanged.class);
        if (event.appId() == null && event.commitSha() == null) {
            metrics.dropped(DropReason.UNTRACKED);
            log.debug("Deploy state change names no app and commit eventId={}", event.eventId());
            return;
        }
        CheckSubject subject =
                CheckSubject.of("DeployStateChanged", event.eventId(), event.orgId(), event.appId(), event.commitSha());
        if (event.toState() == null || !STATE.matcher(event.toState()).matches()) {
            throw new MalformedEventException("DeployStateChanged.toState is not a state name");
        }
        if (!inbox.firstDelivery(CONSUMER, subject.eventId())) {
            return;
        }
        DesiredStateWriter.Result result = writer.apply(
                subject.orgId(),
                subject.appId(),
                subject.commitSha(),
                writer.deployStateChanged(event.toState(), event.url()));
        log.info(
                "Check run desire recorded orgId={} appId={} eventId={} type={} toState={} result={}",
                subject.orgId(),
                subject.appId(),
                subject.eventId(),
                event.eventType(),
                event.toState(),
                result);
    }
}
