package io.pallet.orgteam.outbox.chaos;

import io.pallet.common.events.OrgMemberAdded;
import io.pallet.orgteam.outbox.OutboxWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrent business writers. Each org is owned by exactly one writer thread, so the order in which
 * that thread commits is the order the ledger records and the outbox must preserve. A configurable share of
 * transactions roll back after appending, to prove the relay never publishes them.
 */
final class LoadGenerator implements AutoCloseable {

    private static final int MAX_EVENTS_PER_TRANSACTION = 3;

    private final TransactionTemplate transaction;
    private final OutboxWriter writer;
    private final DeliveryLedger ledger;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger failedTransactions = new AtomicInteger();
    private final ExecutorService executor;
    private final int writers;

    LoadGenerator(
            TransactionTemplate transaction,
            OutboxWriter writer,
            DeliveryLedger ledger,
            List<String> orgs,
            int writers,
            double rollbackRatio,
            Duration meanPause,
            long seed) {
        this.transaction = transaction;
        this.writer = writer;
        this.ledger = ledger;
        this.writers = writers;
        this.executor = Executors.newFixedThreadPool(writers);
        for (int i = 0; i < writers; i++) {
            List<String> owned = new ArrayList<>();
            for (int o = i; o < orgs.size(); o += writers) {
                owned.add(orgs.get(o));
            }
            Random random = new Random(seed + i);
            executor.submit(() -> run(owned, random, rollbackRatio, meanPause));
        }
    }

    private void run(List<String> owned, Random random, double rollbackRatio, Duration meanPause) {
        while (running.get() && !owned.isEmpty()) {
            String orgId = owned.get(random.nextInt(owned.size()));
            List<OrgMemberAdded> events = new ArrayList<>();
            int count = 1 + random.nextInt(MAX_EVENTS_PER_TRANSACTION);
            for (int i = 0; i < count; i++) {
                events.add(OrgMemberAdded.of(orgId, "user-" + UUID.randomUUID(), "member@example.com"));
            }
            boolean rollBack = random.nextDouble() < rollbackRatio;
            try {
                transaction.executeWithoutResult(status -> {
                    events.forEach(writer::append);
                    if (rollBack) {
                        status.setRollbackOnly();
                    }
                });
                events.forEach(rollBack ? ledger::rolledBack : ledger::committed);
            } catch (RuntimeException failure) {
                failedTransactions.incrementAndGet();
                events.forEach(ledger::rolledBack);
            }
            Pauses.sleep(Duration.ofMillis((long) (random.nextDouble() * 2 * meanPause.toMillis())));
        }
    }

    /** Transactions that failed outright, which are treated as rolled back. */
    int failedTransactions() {
        return failedTransactions.get();
    }

    int writers() {
        return writers;
    }

    void stop() {
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                throw new IllegalStateException("writers did not stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while stopping writers", e);
        }
    }

    @Override
    public void close() {
        stop();
    }
}
