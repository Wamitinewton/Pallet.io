package io.pallet.orgteam.outbox.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pallet.common.events.OrgMemberAdded;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.outbox.TopicProbe;
import io.pallet.orgteam.outbox.chaos.PostgresFaults.HeldLock;
import io.pallet.orgteam.outbox.chaos.PostgresFaults.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The relay, or the database under it, dies at the worst moment; the event must survive and surface once to consumers. */
class RelayCrashChaosIntegrationTest extends OutboxChaosSupport {

    private static final Duration LOCK_HELD = Duration.ofSeconds(2);

    @Test
    void aDatabaseFailureAfterTheBrokerAckedRedeliversTheSameEventAndTheConsumerAppliesItOnce() {
        String orgId = newOrg();
        OrgMemberAdded event = memberAdded(orgId);
        double rowFailuresBefore = rowFailures();

        try (Fault ignored = track(postgres.failOutboxWrites(Statement.MARK_PUBLISHED, orgId))) {
            commit(event);
            try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), jsonMapper, MEMBER_ADDED_TOPIC)) {
                assertThat(probe.awaitCount(orgId, 2))
                        .as("the broker acked, the row could not be marked, so the relay sends it again")
                        .hasSizeGreaterThanOrEqualTo(2);
            }
            assertThat(statusOf(event.eventId())).isEqualTo("PENDING");
            assertThat(attemptsOf(event.eventId()))
                    .as("a database failure is not the row's fault")
                    .isZero();
            assertThat(rowFailures()).isEqualTo(rowFailuresBefore);
        }

        awaitPublished(event.eventId());
        Deliveries delivered = deliveries();
        assertThat(delivered.idsForOrg(orgId)).hasSizeGreaterThanOrEqualTo(2).containsOnly(event.eventId());
        OutboxInvariants.verifyAll(ledger, delivered, jdbc, consumer, Integer.MAX_VALUE);
        assertThat(delivered.duplicateCount()).isPositive();
    }

    @Test
    void killingTheRelaySessionWhileItIsPublishingLosesNothing() {
        String orgId = newOrg();
        List<OrgMemberAdded> events = List.of(
                memberAdded(orgId), memberAdded(orgId), memberAdded(orgId), memberAdded(orgId), memberAdded(orgId));

        try (Fault ignored = track(broker.pause())) {
            commit(events.toArray(OrgMemberAdded[]::new));
            await().atMost(WAIT).pollInterval(Duration.ofMillis(20)).until(() -> postgres.killRelayLockHolders() > 0);
        }

        awaitDrained(List.of(orgId));
        OutboxInvariants.verifyAll(
                ledger, deliveries(), jdbc, consumer, properties.outbox().batchSize());
    }

    @Test
    void repeatedRelaySessionKillsUnderLoadLoseNothingAndKeepEveryOrgInOrder() throws Exception {
        long seed = ChaosSeed.next("repeatedRelaySessionKillsUnderLoadLoseNothingAndKeepEveryOrgInOrder");
        List<String> orgs = newOrgs(4);
        AtomicInteger kills = new AtomicInteger();
        AtomicBoolean killing = new AtomicBoolean(true);
        Thread killer = Thread.ofPlatform().start(() -> {
            while (killing.get()) {
                kills.addAndGet(postgres.killRelayLockHolders());
                Pauses.sleep(Duration.ofMillis(60));
            }
        });

        try (LoadGenerator load =
                new LoadGenerator(transaction, writer, ledger, orgs, 2, 0.1, Duration.ofMillis(20), seed)) {
            Pauses.sleep(Duration.ofSeconds(5));
        } finally {
            killing.set(false);
            killer.join();
        }

        awaitDrained(orgs);
        assertThat(kills.get()).as("the relay session was actually killed").isGreaterThanOrEqualTo(3);
        assertThat(ledger.totalCommitted()).as("the load actually ran").isGreaterThan(50);
        OutboxInvariants.verifyAll(
                ledger,
                deliveries(),
                jdbc,
                consumer,
                kills.get() * properties.outbox().batchSize());
    }

    @Test
    void aStandbyRelayTakesOverWhenTheLockHolderDiesWithoutLosingOrReorderingEvents() {
        String orgId = newOrg();
        List<OrgMemberAdded> events = List.of(
                memberAdded(orgId), memberAdded(orgId), memberAdded(orgId), memberAdded(orgId), memberAdded(orgId));

        HeldLock holder = track(postgres.holdRelayLock());
        commit(events.toArray(OrgMemberAdded[]::new));
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), jsonMapper, MEMBER_ADDED_TOPIC)) {
            assertThat(probe.observe(orgId, LOCK_HELD))
                    .as("no relay may publish while the lock is held")
                    .isEmpty();
        }
        assertThat(openRows(List.of(orgId))).isEqualTo(events.size());
        assertThat(gauge(MetricsCatalog.OUTBOX_RELAY_ACTIVE)).isZero();

        assertThat(postgres.terminateBackend(holder.pid())).isTrue();

        awaitDrained(List.of(orgId));
        OutboxInvariants.verifyAll(ledger, deliveries(), jdbc, consumer, 0);
        assertThat(gauge(MetricsCatalog.OUTBOX_RELAY_ACTIVE)).isEqualTo(1);
    }
}
