package io.pallet.buildqueue.observability;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Defers a record of work until the work's transaction commits, so rolled-back work is never reported. */
final class AfterCommit {

    private AfterCommit() {}

    /** Runs {@code record} after the current transaction commits, or now when there is no transaction. */
    static void run(Runnable record) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                record.run();
            }
        });
    }
}
