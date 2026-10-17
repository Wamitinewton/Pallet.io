package io.pallet.buildqueue.intake;

import io.pallet.buildqueue.build.NewBuild;
import io.pallet.common.events.EventHeaders;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.EventPayloads;
import io.pallet.common.inbox.TransactionalInbox;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Intake's entry point. The topic is keyed by {@code orgId} and consumed one record at a time per partition, so an
 * org's pushes are applied in the order git-integration-service published them.
 */
@Component
class PushListener {

    static final String CONSUMER = "git-push-received";
    static final String LISTENER_ID = "push-listener";

    private final JsonMapper jsonMapper;
    private final TransactionalInbox inbox;
    private final BuildIntake intake;

    PushListener(JsonMapper jsonMapper, TransactionalInbox inbox, BuildIntake intake) {
        this.jsonMapper = jsonMapper;
        this.inbox = inbox;
        this.intake = intake;
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = Topics.GIT_PUSH_RECEIVED)
    @Transactional
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        GitPushReceived push = EventPayloads.read(jsonMapper, record.value(), GitPushReceived.class);
        NewBuild build = PushValidator.validate(push, header(record, EventHeaders.TRACEPARENT));
        // Ordering is per record key; a push keyed by anything but its org has no ordering guarantee to rely on.
        if (!build.orgId().equals(record.key())) {
            throw new MalformedPushException("GitPushReceived record key is not its orgId");
        }
        if (!inbox.firstDelivery(CONSUMER, build.pushEventId())) {
            return;
        }
        intake.accept(build);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
