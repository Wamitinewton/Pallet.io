package io.pallet.build.intake;

import io.pallet.build.config.BuildProperties;
import io.pallet.build.domain.NewBuild;
import io.pallet.common.events.BuildDispatched;
import io.pallet.common.events.EventHeaders;
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
 * Intake's entry point. A malformed dispatch is dead-lettered before it is claimed; anything else is claimed by event
 * id first, then applied in the same transaction.
 */
@Component
class DispatchListener {

    static final String CONSUMER = "build-dispatched";
    static final String LISTENER_ID = "dispatch-listener";

    private final JsonMapper jsonMapper;
    private final TransactionalInbox inbox;
    private final BuildIntake intake;
    private final DispatchValidator validator;

    DispatchListener(JsonMapper jsonMapper, TransactionalInbox inbox, BuildIntake intake, BuildProperties properties) {
        this.jsonMapper = jsonMapper;
        this.inbox = inbox;
        this.intake = intake;
        this.validator = new DispatchValidator(properties.execution().maxDurationCap());
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = Topics.BUILD_DISPATCHED)
    @Transactional
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        BuildDispatched dispatch = EventPayloads.read(jsonMapper, record.value(), BuildDispatched.class);
        NewBuild build = validator.validate(dispatch, header(record, EventHeaders.TRACEPARENT));
        if (!build.orgId().equals(record.key())) {
            throw new MalformedDispatchException("BuildDispatched record key is not its orgId");
        }
        if (!inbox.firstDelivery(CONSUMER, dispatch.eventId())) {
            return;
        }
        intake.handle(build);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
