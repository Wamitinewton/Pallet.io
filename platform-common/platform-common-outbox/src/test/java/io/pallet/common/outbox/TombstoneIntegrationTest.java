package io.pallet.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.events.Topics;
import io.pallet.common.outbox.TopicProbe.Received;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

@OutboxIntegrationTest
class TombstoneIntegrationTest extends OutboxIntegrationSupport {

    @Test
    void aTombstoneRowPublishesANullValueUnderItsKeyAfterTheRecordItDeletes() {
        String orgId = newOrgId();
        String userId = "user-" + UUID.randomUUID();
        String key = OrgMembershipChanged.key(orgId, userId);
        OrgMembershipChanged removed = OrgMembershipChanged.of(orgId, userId, "developer", "REMOVED", 4);
        transaction.executeWithoutResult(status -> {
            writer.append(removed, key);
            writer.appendTombstone(orgId, Topics.ORG_MEMBERSHIP_CHANGED, key);
        });

        newRelay().tick();

        try (TopicProbe probe = probe(Topics.ORG_MEMBERSHIP_CHANGED)) {
            List<Received> records = probe.awaitCount(key, 2);
            assertThat(records).hasSize(2);
            assertThat(records.get(0).eventId()).isEqualTo(removed.eventId());
            assertThat(records.get(1).body()).isNull();
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from " + OUTBOX + " where org_id = ? and status <> 'PUBLISHED'",
                        Integer.class,
                        orgId))
                .isZero();
    }

    @Test
    void aTombstoneForATopicThatIsNotCompactedIsRefusedAndWritesNothing() {
        String orgId = newOrgId();

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                        status -> writer.appendTombstone(orgId, Topics.ORG_MEMBER_ADDED, orgId + ":user")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("select count(*) from " + OUTBOX + " where org_id = ?", Integer.class, orgId))
                .isZero();
    }

    @Test
    void theDatabaseRejectsATombstoneRowThatCarriesAPayload() {
        assertThatThrownBy(() -> jdbc.update(
                        "insert into " + OUTBOX + " (event_id, org_id, event_type, payload, record_key, tombstone) "
                                + "values (?, ?, ?, '{}'::jsonb, 'k', true)",
                        UUID.randomUUID(),
                        newOrgId(),
                        Topics.ORG_MEMBERSHIP_CHANGED))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRejectsATombstoneRowWithoutAKey() {
        assertThatThrownBy(() -> jdbc.update(
                        "insert into " + OUTBOX + " (event_id, org_id, event_type, tombstone) values (?, ?, ?, true)",
                        UUID.randomUUID(),
                        newOrgId(),
                        Topics.ORG_MEMBERSHIP_CHANGED))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aHandWrittenTombstoneForATopicThatIsNotCompactedParksInsteadOfStallingTheRelay() {
        String orgId = newOrgId();
        UUID tombstoneId = UUID.randomUUID();
        jdbc.update(
                "insert into " + OUTBOX
                        + " (event_id, org_id, event_type, record_key, tombstone) values (?, ?, ?, ?, true)",
                tombstoneId,
                orgId,
                Topics.ORG_MEMBER_ADDED,
                orgId + ":user");
        OutboxRelay relay = newRelay();

        for (int attempt = 0; attempt < properties.maxAttempts(); attempt++) {
            makeEligibleNow(tombstoneId);
            relay.tick();
        }

        assertThat(statusOf(tombstoneId)).isEqualTo("PARKED");
        assertThat(relay.brokerBackoff()).isZero();
    }
}
