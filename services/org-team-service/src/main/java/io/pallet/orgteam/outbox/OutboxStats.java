package io.pallet.orgteam.outbox;

public record OutboxStats(long pending, long parked, double oldestPendingAgeSeconds) {

    static final OutboxStats EMPTY = new OutboxStats(0, 0, 0);
}
