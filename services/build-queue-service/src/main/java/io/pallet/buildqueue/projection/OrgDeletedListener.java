package io.pallet.buildqueue.projection;

import io.pallet.buildqueue.cancel.QueueTeardown;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.EventPayloads;
import io.pallet.common.inbox.TransactionalInbox;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tombstones the org, removes its memberships, leaving their versions for the state topic's own records, and cancels its
 * waiting builds. The tombstone's upsert holds the org row lock, so intake can't admit a push until the cancellations
 * commit with it.
 */
@Component
class OrgDeletedListener {

    static final String CONSUMER = "org-deleted";

    private static final String EVENT = "OrgDeleted";

    private static final Logger log = LoggerFactory.getLogger(OrgDeletedListener.class);

    private final JsonMapper jsonMapper;
    private final TransactionalInbox inbox;
    private final OrgProjectionRepository orgs;
    private final MembershipProjectionRepository memberships;
    private final QueueTeardown teardown;

    OrgDeletedListener(
            JsonMapper jsonMapper,
            TransactionalInbox inbox,
            OrgProjectionRepository orgs,
            MembershipProjectionRepository memberships,
            QueueTeardown teardown) {
        this.jsonMapper = jsonMapper;
        this.inbox = inbox;
        this.orgs = orgs;
        this.memberships = memberships;
        this.teardown = teardown;
    }

    @KafkaListener(id = "org-deleted-listener", idIsGroup = false, topics = Topics.ORG_DELETED)
    @Transactional
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        OrgDeleted event = EventPayloads.read(jsonMapper, record.value(), OrgDeleted.class);
        EventFields.requireEventId(EVENT, event.eventId());
        String orgId = EventFields.requireId(EVENT, "orgId", event.orgId());
        if (!inbox.firstDelivery(CONSUMER, event.eventId())) {
            return;
        }
        orgs.upsertDeleted(orgId);
        int removed = memberships.markAllRemoved(orgId);
        int cancelled = teardown.cancelQueuedForOrg(orgId);
        log.info(
                "Org deletion projected orgId={} eventId={} membershipsRemoved={} buildsCancelled={}",
                orgId,
                event.eventId(),
                removed,
                cancelled);
    }
}
