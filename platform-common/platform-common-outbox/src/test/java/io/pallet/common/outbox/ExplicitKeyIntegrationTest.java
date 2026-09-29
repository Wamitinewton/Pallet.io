package io.pallet.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.EventHeaders;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.outbox.TopicProbe.Received;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@OutboxIntegrationTest
class ExplicitKeyIntegrationTest extends OutboxIntegrationSupport {

    @Test
    void aRowWithARecordKeyIsPublishedUnderThatKeyAndOneWithoutUnderItsOrg() {
        String orgId = newOrgId();
        String userId = "user-" + UUID.randomUUID();
        String key = OrgMembershipChanged.key(orgId, userId);
        OrgMembershipChanged keyed = OrgMembershipChanged.of(orgId, userId, "admin", "ACTIVE", 1);
        OrgMemberAdded unkeyed = memberAdded(orgId);
        transaction.executeWithoutResult(status -> {
            writer.append(keyed, key);
            writer.append(unkeyed);
        });

        newRelay().tick();

        try (TopicProbe probe = probe(OrgMembershipChanged.TYPE, OrgMemberAdded.TYPE)) {
            List<Received> underKey = probe.awaitCount(key, 1);
            assertThat(underKey).singleElement().satisfies(record -> {
                assertThat(record.topic()).isEqualTo(OrgMembershipChanged.TYPE);
                assertThat(record.eventId()).isEqualTo(keyed.eventId());
                assertThat(record.headers()).containsEntry(EventHeaders.ORG_ID, orgId);
            });
            assertThat(probe.awaitCount(orgId, 1))
                    .singleElement()
                    .satisfies(record -> assertThat(record.eventId()).isEqualTo(unkeyed.eventId()));
        }
    }

    @Test
    void anOrgStaysInOrderAcrossRowsWithDifferentKeys() {
        String orgId = newOrgId();
        String userId = "user-" + UUID.randomUUID();
        String key = OrgMembershipChanged.key(orgId, userId);
        OrgMembershipChanged first = OrgMembershipChanged.of(orgId, userId, "developer", "ACTIVE", 1);
        OrgMemberAdded between = memberAdded(orgId);
        OrgMembershipChanged last = OrgMembershipChanged.of(orgId, userId, "admin", "ACTIVE", 2);
        transaction.executeWithoutResult(status -> writer.append(first, key));
        commit(between);
        transaction.executeWithoutResult(status -> writer.append(last, key));
        jdbc.update(
                "update " + OUTBOX + " set next_attempt_at = now() + interval '1 hour' where event_id = ?",
                between.eventId());

        newRelay().tick();

        assertThat(statusOf(first.eventId())).isEqualTo("PUBLISHED");
        assertThat(statusOf(between.eventId())).isEqualTo("PENDING");
        assertThat(statusOf(last.eventId()))
                .as("a later row under another key still waits behind its org's earlier row")
                .isEqualTo("PENDING");

        makeEligibleNow(between.eventId());
        newRelay().tick();

        try (TopicProbe probe = probe(OrgMembershipChanged.TYPE)) {
            assertThat(probe.awaitCount(key, 2).stream().map(Received::eventId))
                    .containsExactly(first.eventId(), last.eventId());
        }
    }
}
