package io.pallet.orgteam.org;

import io.pallet.common.events.OrgProvisioned;
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
class OrgProvisionedListener {

    static final String CONSUMER = MetricsCatalog.LISTENER_ORG_PROVISIONED;

    private final JsonMapper jsonMapper;
    private final TransactionalInbox inbox;
    private final OrgTeamMetrics metrics;
    private final OrgService orgService;

    OrgProvisionedListener(
            JsonMapper jsonMapper, TransactionalInbox inbox, OrgTeamMetrics metrics, OrgService orgService) {
        this.jsonMapper = jsonMapper;
        this.inbox = inbox;
        this.metrics = metrics;
        this.orgService = orgService;
    }

    @KafkaListener(id = "org-provisioned-listener", idIsGroup = false, topics = Topics.ORG_PROVISIONED)
    @Transactional
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        metrics.countingFailures(CONSUMER, () -> handle(record));
    }

    private void handle(ConsumerRecord<String, JsonNode> record) {
        OrgProvisioned event = EventPayloads.read(jsonMapper, record.value(), OrgProvisioned.class);
        if (event.eventId() == null) {
            throw new MalformedEventException("OrgProvisioned.eventId is required");
        }
        if (!inbox.firstDelivery(CONSUMER, event.eventId())) {
            return;
        }
        orgService.provision(event);
    }
}
