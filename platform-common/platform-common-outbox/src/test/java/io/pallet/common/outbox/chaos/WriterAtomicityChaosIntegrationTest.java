package io.pallet.common.outbox.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.outbox.OutboxTestApplication.ProvisioningListener;
import io.pallet.common.outbox.chaos.PostgresFaults.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

/** State change and event row are one atomic unit: both exist or neither does. */
class WriterAtomicityChaosIntegrationTest extends OutboxChaosSupport {

    private static final Duration COMMIT_BUDGET = Duration.ofSeconds(2);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private ProvisioningListener listener;

    @Test
    void aShareOfRolledBackTransactionsUnderLoadNeverReachesTheBroker() {
        long seed = ChaosSeed.next("aShareOfRolledBackTransactionsUnderLoadNeverReachesTheBroker");
        List<String> orgs = newOrgs(4);

        try (LoadGenerator load =
                new LoadGenerator(transaction, writer, ledger, orgs, 4, 0.5, Duration.ofMillis(15), seed)) {
            Pauses.sleep(Duration.ofSeconds(4));
        }

        awaitDrained(orgs);
        assertThat(ledger.totalCommitted()).as("the load actually ran").isGreaterThan(30);
        assertThat(orgs.stream()
                        .mapToInt(org -> ledger.rolledBackFor(org).size())
                        .sum())
                .as("rollbacks actually happened")
                .isPositive();
        OutboxInvariants.verifyAll(ledger, deliveries(), jdbc, consumer, 0);
        for (String org : orgs) {
            for (UUID neverCommitted : ledger.rolledBackFor(org)) {
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM outbox_test.outbox_events WHERE event_id = ?",
                                Integer.class,
                                neverCommitted))
                        .as("outbox row of a rolled-back transaction")
                        .isZero();
            }
        }
    }

    @Test
    void anExceptionAfterAppendRollsBackTheEventAndTheBusinessWriteTogether() {
        String orgId = newOrg();
        OrgMemberAdded event = memberAdded(orgId);
        UUID businessRow = UUID.randomUUID();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    insertBusinessRow(businessRow);
                    writer.append(event);
                    throw new IllegalStateException("crash after the append, before the commit");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(businessRowExists(businessRow)).isFalse();
        assertThat(outboxRows(event.eventId())).isZero();
        Pauses.sleep(Duration.ofSeconds(1));
        ledger.rolledBack(event);
        OutboxInvariants.noPhantoms(ledger, deliveries());
    }

    @Test
    void aDuplicateEventIdRejectsTheWholeTransaction() {
        String orgId = newOrg();
        OrgMemberAdded event = memberAdded(orgId);
        UUID businessRow = UUID.randomUUID();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    insertBusinessRow(businessRow);
                    writer.append(event);
                    writer.append(event);
                }))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(businessRowExists(businessRow)).isFalse();
        assertThat(outboxRows(event.eventId())).isZero();
    }

    @Test
    void writersKeepCommittingWhileTheBrokerIsDownAndEverythingDrainsAfterwards() {
        String orgId = newOrg();

        try (Fault ignored = track(broker.pause())) {
            for (int i = 0; i < 20; i++) {
                long started = System.nanoTime();
                commit(memberAdded(orgId));
                assertThat(Duration.ofNanos(System.nanoTime() - started))
                        .as("commit %d must not wait for the broker", i)
                        .isLessThan(COMMIT_BUDGET);
            }
            assertThat(openRows(List.of(orgId))).isEqualTo(20);
        }

        awaitDrained(List.of(orgId));
        OutboxInvariants.verifyAll(ledger, deliveries(), jdbc, consumer, 4);
    }

    @Test
    void aConsumerCrashMidTransactionLeavesNoPartialStateAndTheRedeliverySucceeds() {
        String orgId = newOrg();
        OrgProvisioned provisioned =
                OrgProvisioned.of(orgId, "Chaos Org", "chaos-" + orgId, "owner-" + orgId, "owner@example.com", "Owner");
        int failedBefore = listener.failed();
        int processedBefore = listener.processed();

        try (Fault ignored = track(postgres.failOutboxWrites(Statement.INSERT, orgId))) {
            publisher.publish(provisioned);

            await().atMost(WAIT)
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> assertThat(listener.failed()).isGreaterThan(failedBefore));
            assertThat(claims(provisioned.eventId())).as("inbox claim").isZero();
            assertThat(rowsFor("outbox_events", orgId)).as("outbox rows").isZero();
        }

        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(claims(provisioned.eventId())).isEqualTo(1));
        assertThat(memberAddedRows(orgId)).isEqualTo(1);
        assertThat(listener.processed()).isEqualTo(processedBefore + 1);

        UUID eventId = jdbc.queryForObject(
                "SELECT event_id FROM outbox_test.outbox_events WHERE org_id = ? AND event_type = ?",
                UUID.class,
                orgId,
                OrgMemberAdded.TYPE);
        ledger.committed(orgId, eventId);
        awaitDrained(List.of(orgId));
        Deliveries delivered = deliveries();
        OutboxInvariants.noLoss(ledger, delivered);
        OutboxInvariants.noStrangers(ledger, delivered);
        OutboxInvariants.duplicatesAtMost(delivered, 0);
    }

    private int claims(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_test.processed_events WHERE event_id = ? AND consumer = ?",
                Integer.class,
                eventId,
                ProvisioningListener.CONSUMER);
    }

    private void insertBusinessRow(UUID eventId) {
        jdbc.update(
                "INSERT INTO outbox_test.processed_events (event_id, consumer) VALUES (?, 'chaos-business-row')",
                eventId);
    }

    private boolean businessRowExists(UUID eventId) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_test.processed_events WHERE event_id = ? AND consumer = 'chaos-business-row'",
                        Integer.class,
                        eventId)
                > 0;
    }

    private int outboxRows(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_test.outbox_events WHERE event_id = ?", Integer.class, eventId);
    }

    private int memberAddedRows(String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_test.outbox_events WHERE org_id = ? AND event_type = ?",
                Integer.class,
                orgId,
                OrgMemberAdded.TYPE);
    }

    private int rowsFor(String table, String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_test." + table + " WHERE org_id = ?", Integer.class, orgId);
    }
}
