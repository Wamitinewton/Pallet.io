package io.pallet.common.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.outbox.OutboxIntegrationTest;
import io.pallet.common.outbox.OutboxTestApplication;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@OutboxIntegrationTest
class TransactionalInboxIntegrationTest {

    private static final String PROCESSED = OutboxTestApplication.SCHEMA + ".processed_events";
    private static final String CONSUMER = "org-provisioned";
    private static final int CONTENDERS = 50;

    @Autowired
    private TransactionalInbox inbox;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry registry;

    @BeforeEach
    void cleanInbox() {
        jdbc.execute("TRUNCATE " + PROCESSED);
    }

    @Test
    void theFirstClaimWinsAndARedeliveryLoses() {
        UUID eventId = UUID.randomUUID();
        double duplicatesBefore = duplicates(CONSUMER);

        assertThat(claim(CONSUMER, eventId)).isTrue();
        assertThat(claim(CONSUMER, eventId)).isFalse();
        assertThat(duplicates(CONSUMER)).isEqualTo(duplicatesBefore + 1);
    }

    @Test
    void aRolledBackClaimLeavesNothingBehind() {
        UUID eventId = UUID.randomUUID();

        Boolean claimedInRolledBackTransaction = transaction.execute(status -> {
            boolean claimed = inbox.firstDelivery(CONSUMER, eventId);
            status.setRollbackOnly();
            return claimed;
        });

        assertThat(claimedInRolledBackTransaction).isTrue();
        assertThat(rowCount(eventId)).isZero();
        assertThat(claim(CONSUMER, eventId)).isTrue();
    }

    @Test
    void theSameEventUnderAnotherConsumerIsIndependent() {
        UUID eventId = UUID.randomUUID();

        assertThat(claim(CONSUMER, eventId)).isTrue();
        assertThat(claim("org-invite-accepted", eventId)).isTrue();
    }

    @Test
    void fiftyConcurrentClaimsOfOneEventYieldExactlyOneWinner() throws Exception {
        UUID eventId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(CONTENDERS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < CONTENDERS; i++) {
                claims.add(executor.submit(() -> {
                    start.await();
                    return claim(CONSUMER, eventId);
                }));
            }
            start.countDown();
            long winners = 0;
            for (Future<Boolean> claim : claims) {
                if (claim.get()) {
                    winners++;
                }
            }
            assertThat(winners).isOne();
        } finally {
            executor.shutdownNow();
        }
        assertThat(rowCount(eventId)).isOne();
    }

    @Test
    void claimingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> inbox.firstDelivery(CONSUMER, UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void retentionDeletesOnlyClaimsOlderThanTheWindow() {
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO " + PROCESSED
                        + " (event_id, consumer, processed_at) VALUES (?, ?, now() - interval '15 days')",
                old,
                CONSUMER);
        claim(CONSUMER, recent);

        int deleted = inbox.deleteProcessedOlderThan(Duration.ofDays(14), 100);

        assertThat(deleted).isOne();
        assertThat(rowCount(old)).isZero();
        assertThat(rowCount(recent)).isOne();
    }

    private boolean claim(String consumer, UUID eventId) {
        return Boolean.TRUE.equals(transaction.execute(status -> inbox.firstDelivery(consumer, eventId)));
    }

    private int rowCount(UUID eventId) {
        return jdbc.queryForObject("select count(*) from " + PROCESSED + " where event_id = ?", Integer.class, eventId);
    }

    private double duplicates(String consumer) {
        Counter counter = registry.find("outboxtest." + TransactionalInbox.DUPLICATES)
                .tag(TransactionalInbox.TAG_LISTENER, consumer)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
