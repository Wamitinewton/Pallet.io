package io.pallet.orgteam.outbox.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.outbox.chaos.PostgresFaults.Statement;
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
                                "SELECT count(*) FROM org_team.outbox_events WHERE event_id = ?",
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
        double failedBefore = counter(
                MetricsCatalog.EVENTS_FAILED, MetricsCatalog.TAG_LISTENER, MetricsCatalog.LISTENER_ORG_PROVISIONED);
        double processedBefore = counter(
                MetricsCatalog.EVENTS_PROCESSED, MetricsCatalog.TAG_LISTENER, MetricsCatalog.LISTENER_ORG_PROVISIONED);

        try (Fault ignored = track(postgres.failOutboxWrites(Statement.INSERT, orgId))) {
            publisher.publish(OrgProvisioned.of(
                    orgId, "Chaos Org", "chaos-" + orgId, "owner-" + orgId, "owner@example.com", "Owner"));

            await().atMost(WAIT)
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> assertThat(counter(
                                    MetricsCatalog.EVENTS_FAILED,
                                    MetricsCatalog.TAG_LISTENER,
                                    MetricsCatalog.LISTENER_ORG_PROVISIONED))
                            .isGreaterThan(failedBefore));
            assertThat(rowsFor("organizations", orgId)).as("organization").isZero();
            assertThat(rowsFor("memberships", orgId)).as("membership").isZero();
            assertThat(rowsFor("outbox_events", orgId)).as("outbox rows").isZero();
        }

        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(rowsFor("organizations", orgId)).isEqualTo(1));
        assertThat(rowsFor("memberships", orgId)).isEqualTo(1);
        assertThat(memberAddedRows(orgId)).isEqualTo(1);
        assertThat(counter(
                        MetricsCatalog.EVENTS_PROCESSED,
                        MetricsCatalog.TAG_LISTENER,
                        MetricsCatalog.LISTENER_ORG_PROVISIONED))
                .isEqualTo(processedBefore + 1);

        UUID eventId = jdbc.queryForObject(
                "SELECT event_id FROM org_team.outbox_events WHERE org_id = ? AND event_type = ?",
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

    private void insertBusinessRow(UUID eventId) {
        jdbc.update(
                "INSERT INTO org_team.processed_events (event_id, consumer) VALUES (?, 'chaos-business-row')", eventId);
    }

    private boolean businessRowExists(UUID eventId) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.processed_events WHERE event_id = ? AND consumer = 'chaos-business-row'",
                        Integer.class,
                        eventId)
                > 0;
    }

    private int outboxRows(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.outbox_events WHERE event_id = ?", Integer.class, eventId);
    }

    private int memberAddedRows(String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ? AND event_type = ?",
                Integer.class,
                orgId,
                OrgMemberAdded.TYPE);
    }

    private int rowsFor(String table, String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team." + table + " WHERE org_id = ?", Integer.class, orgId);
    }
}
