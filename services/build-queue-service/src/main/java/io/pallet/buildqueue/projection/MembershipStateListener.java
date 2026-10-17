package io.pallet.buildqueue.projection;

import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.buildqueue.projection.MembershipProjection.Status;
import io.pallet.buildqueue.security.Role;
import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.EventPayloads;
import io.pallet.common.inbox.MalformedEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maintains {@code org_memberships} from the compacted {@code org.membership.changed} topic (ADR-0019). Runs in its
 * own consumer group so the read model can be rebuilt by resetting that group alone.
 */
@Component
class MembershipStateListener {

    static final String LISTENER_ID = "membership-state-listener";

    private static final String EVENT = "OrgMembershipChanged";
    private static final char KEY_SEPARATOR = ':';

    private static final Logger log = LoggerFactory.getLogger(MembershipStateListener.class);

    private final JsonMapper jsonMapper;
    private final MembershipProjectionRepository memberships;
    private final OrgProjectionRepository orgs;
    private final QueueMetrics metrics;

    MembershipStateListener(
            JsonMapper jsonMapper,
            MembershipProjectionRepository memberships,
            OrgProjectionRepository orgs,
            QueueMetrics metrics) {
        this.jsonMapper = jsonMapper;
        this.memberships = memberships;
        this.orgs = orgs;
        this.metrics = metrics;
    }

    // No inbox: a rebuild replays the topic from offset zero, and the version check in applyState is the idempotency.
    @KafkaListener(
            id = LISTENER_ID,
            groupId = "${spring.kafka.consumer.group-id:${spring.application.name}}.membership-state",
            topics = Topics.ORG_MEMBERSHIP_CHANGED,
            properties = "auto.offset.reset=earliest")
    @Transactional
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        MembershipKey key = MembershipKey.parse(record.key());
        if (record.value() == null) {
            memberships.deleteMembership(key.orgId(), key.userId());
            log.info("Membership tombstone applied orgId={}", key.orgId());
            return;
        }
        OrgMembershipChanged event = EventPayloads.read(jsonMapper, record.value(), OrgMembershipChanged.class);
        validate(event, key);
        Role role = Role.fromWireName(event.role())
                .orElseThrow(() -> new MalformedEventException("unknown membership role"));
        Status status = Status.fromWire(event.status());

        orgs.ensureExists(key.orgId());
        int applied = memberships.applyState(
                key.orgId(), key.userId(), role.wireName(), status.name(), event.membershipVersion());
        metrics.membershipApplied(event.occurredAt());
        log.debug(
                "Membership state orgId={} eventId={} version={} applied={}",
                key.orgId(),
                event.eventId(),
                event.membershipVersion(),
                applied == 1);
    }

    private static void validate(OrgMembershipChanged event, MembershipKey key) {
        EventFields.requireEventId(EVENT, event.eventId());
        EventFields.requireOccurredAt(EVENT, event.occurredAt());
        if (event.membershipVersion() < 0) {
            throw new MalformedEventException(EVENT + ".membershipVersion is negative");
        }
        if (!key.orgId().equals(event.orgId()) || !key.userId().equals(event.userId())) {
            throw new MalformedEventException(EVENT + " payload does not match its record key");
        }
    }

    record MembershipKey(String orgId, String userId) {

        /** @throws MalformedEventException unless {@code key} is {@code orgId:userId} with exactly one separator */
        static MembershipKey parse(String key) {
            if (key == null) {
                throw new MalformedEventException("membership record has no key");
            }
            int separator = key.indexOf(KEY_SEPARATOR);
            if (separator < 0 || key.indexOf(KEY_SEPARATOR, separator + 1) >= 0) {
                throw new MalformedEventException("membership record key must be orgId:userId");
            }
            return new MembershipKey(
                    EventFields.requireId("membership key", "orgId", key.substring(0, separator)),
                    EventFields.requireId("membership key", "userId", key.substring(separator + 1)));
        }
    }
}
