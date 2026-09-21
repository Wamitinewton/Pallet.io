package io.pallet.orgteam.outbox.chaos;

/** An injected failure that stays in effect until closed. Closing is idempotent. */
@FunctionalInterface
interface Fault extends AutoCloseable {

    @Override
    void close();
}
