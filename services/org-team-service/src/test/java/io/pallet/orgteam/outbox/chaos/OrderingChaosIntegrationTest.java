package io.pallet.orgteam.outbox.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.OrgMemberAdded;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Outbox ids are assigned at insert, not at commit. These tests pin down what per-org ordering means
 * when transactions overlap.
 */
class OrderingChaosIntegrationTest extends OutboxChaosSupport {

    @Test
    void sequentialTransactionsAreDeliveredInCommitOrder() {
        String orgId = newOrg();
        for (int i = 0; i < 20; i++) {
            commit(memberAdded(orgId));
        }

        awaitDrained(List.of(orgId));
        Deliveries delivered = deliveries();
        OutboxInvariants.orderPreserved(ledger, delivered);
        OutboxInvariants.duplicatesAtMost(delivered, 0);
    }

    @Test
    void anUncommittedEventInAnotherOrgNeverHoldsBackThisOrg() throws Exception {
        String slowOrg = newOrg();
        String otherOrg = newOrg();
        OrgMemberAdded slow = memberAdded(slowOrg);
        OrgMemberAdded other = memberAdded(otherOrg);
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> slowTransaction = executor.submit(() -> transaction.executeWithoutResult(status -> {
                writer.append(slow);
                appended.countDown();
                awaitUnchecked(release);
            }));
            assertThat(appended.await(10, TimeUnit.SECONDS)).isTrue();

            commit(other);
            awaitPublished(other.eventId());

            release.countDown();
            slowTransaction.get(10, TimeUnit.SECONDS);
            ledger.committed(slow);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        awaitDrained(List.of(slowOrg, otherOrg));
        OutboxInvariants.verifyAll(ledger, deliveries(), jdbc, consumer, 0);
    }

    @Test
    void anEventThatCommitsLateIsStillDeliveredBeforeOneAppendedAfterIt() throws Exception {
        String orgId = newOrg();
        OrgMemberAdded appendedFirstCommitsLast = memberAdded(orgId);
        OrgMemberAdded appendedSecondCommitsFirst = memberAdded(orgId);
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> slowTransaction = executor.submit(() -> transaction.executeWithoutResult(status -> {
                writer.append(appendedFirstCommitsLast);
                appended.countDown();
                awaitUnchecked(release);
            }));
            assertThat(appended.await(10, TimeUnit.SECONDS)).isTrue();

            transaction.executeWithoutResult(status -> writer.append(appendedSecondCommitsFirst));
            Pauses.sleep(Duration.ofMillis(500));
            assertThat(statusOf(appendedSecondCommitsFirst.eventId())).isEqualTo("PENDING");

            release.countDown();
            slowTransaction.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        ledger.committed(appendedFirstCommitsLast);
        ledger.committed(appendedSecondCommitsFirst);

        awaitDrained(List.of(orgId));
        Deliveries delivered = deliveries();
        OutboxInvariants.orderPreserved(ledger, delivered);
        OutboxInvariants.duplicatesAtMost(delivered, 0);
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
