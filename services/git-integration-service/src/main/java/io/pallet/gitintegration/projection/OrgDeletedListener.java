package io.pallet.gitintegration.projection;

import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.EventPayloads;
import io.pallet.common.inbox.MalformedEventException;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.gitintegration.installation.ConnectionTeardown;
import io.pallet.gitintegration.installation.TeardownResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records the deletion for the membership gate and ends every connection of the org. Other orgs' links to the same
 * installations stay, and the installations themselves are left on GitHub.
 */
@Component
class OrgDeletedListener {

    static final String CONSUMER = "org-deleted-projection";

    private static final Logger log = LoggerFactory.getLogger(OrgDeletedListener.class);

    private final JsonMapper jsonMapper;
    private final TransactionalInbox inbox;
    private final DeletedOrgRepository deletedOrgs;
    private final MembershipProjectionRepository memberships;
    private final ConnectionTeardown teardown;
    private final ProjectionMetrics metrics;

    OrgDeletedListener(
            JsonMapper jsonMapper,
            TransactionalInbox inbox,
            DeletedOrgRepository deletedOrgs,
            MembershipProjectionRepository memberships,
            ConnectionTeardown teardown,
            ProjectionMetrics metrics) {
        this.jsonMapper = jsonMapper;
        this.inbox = inbox;
        this.deletedOrgs = deletedOrgs;
        this.memberships = memberships;
        this.teardown = teardown;
        this.metrics = metrics;
    }

    @KafkaListener(id = "org-deleted-listener", idIsGroup = false, topics = Topics.ORG_DELETED)
    @Transactional
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        metrics.countingFailures(CONSUMER, () -> handle(record));
    }

    private void handle(ConsumerRecord<String, JsonNode> record) {
        OrgDeleted event = EventPayloads.read(jsonMapper, record.value(), OrgDeleted.class);
        if (event.eventId() == null) {
            throw new MalformedEventException("OrgDeleted.eventId is required");
        }
        if (event.occurredAt() == null) {
            throw new MalformedEventException("OrgDeleted.occurredAt is required");
        }
        EventPayloads.requireText("OrgDeleted", "orgId", event.orgId(), AppEventListener.MAX_ORG_ID_LENGTH);
        if (!inbox.firstDelivery(CONSUMER, event.eventId())) {
            return;
        }
        deletedOrgs.record(event.orgId(), event.occurredAt());
        int removed = memberships.markAllRemoved(event.orgId());
        TeardownResult result = teardown.orgDeleted(event.orgId());
        log.info(
                "Org deletion projected orgId={} eventId={} membershipsRemoved={} repoLinksDisconnected={}",
                event.orgId(),
                event.eventId(),
                removed,
                result.disconnected().size());
    }
}
