package io.pallet.buildqueue.build;

/**
 * A build's place in its lifecycle. Status only moves to a higher rank. {@code EXPIRED} is not terminal: a late
 * result from build-service still moves it to {@code SUCCEEDED} or {@code FAILED}.
 */
public enum BuildStatus {
    QUEUED(0),
    DISPATCHED(1),
    RUNNING(2),
    EXPIRED(3),
    SUCCEEDED(4),
    FAILED(4),
    SUPERSEDED(4),
    CANCELLED(4),
    REJECTED(4);

    private static final int FINAL_RANK = 4;

    private final int rank;

    BuildStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** Holds one of the app's single in-flight slot and one of its org's concurrency slots. */
    public boolean isInFlight() {
        return this == DISPATCHED || this == RUNNING;
    }

    public boolean isTerminal() {
        return rank == FINAL_RANK;
    }
}
