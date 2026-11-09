package io.pallet.build.observability;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Defers a side effect such as a metric until the surrounding transaction commits, so a rollback never records it. */
public final class AfterCommit {

    private AfterCommit() {}

    /** Runs {@code action} at once when no transaction is active. */
    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
