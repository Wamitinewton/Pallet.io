package io.pallet.orgteam.invite;

import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.Topics;
import io.pallet.orgteam.inbox.EventPayloads;
import io.pallet.orgteam.inbox.MalformedEventException;
import io.pallet.orgteam.inbox.TransactionalInbox;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class OrgInviteAcceptedListener {

    static final String CONSUMER = MetricsCatalog.LISTENER_ORG_INVITE_ACCEPTED;

    private final JsonMapper jsonMapper;
    private final TransactionalInbox inbox;
    private final OrgTeamMetrics metrics;
    private final InviteAcceptanceService acceptance;

    OrgInviteAcceptedListener(
            JsonMapper jsonMapper,
            TransactionalInbox inbox,
            OrgTeamMetrics metrics,
            InviteAcceptanceService acceptance) {
        this.jsonMapper = jsonMapper;
        this.inbox = inbox;
        this.metrics = metrics;
        this.acceptance = acceptance;
    }

    @KafkaListener(id = "org-invite-accepted-listener", idIsGroup = false, topics = Topics.ORG_INVITE_ACCEPTED)
    @Transactional
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        metrics.countingFailures(CONSUMER, () -> handle(record));
    }

    private void handle(ConsumerRecord<String, JsonNode> record) {
        OrgInviteAccepted event = EventPayloads.read(jsonMapper, record.value(), OrgInviteAccepted.class);
        if (event.eventId() == null) {
            throw new MalformedEventException("OrgInviteAccepted.eventId is required");
        }
        if (!inbox.firstDelivery(CONSUMER, event.eventId())) {
            return;
        }
        acceptance.handle(event);
    }
}
