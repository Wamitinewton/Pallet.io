package io.pallet.buildqueue.build;

import io.pallet.common.events.GitPushReceived;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/** Orders an org's own waiting builds: someone watching a button goes before a background push. */
public enum PriorityClass {
    INTERACTIVE(0),
    AUTOMATIC(1);

    private static final Map<String, PriorityClass> BY_TRIGGER = Map.of(
            GitPushReceived.TRIGGER_MANUAL, INTERACTIVE,
            GitPushReceived.TRIGGER_LINKED, INTERACTIVE,
            GitPushReceived.TRIGGER_WEBHOOK, AUTOMATIC,
            GitPushReceived.TRIGGER_RECONCILED, AUTOMATIC);

    private final short rank;

    PriorityClass(int rank) {
        this.rank = (short) rank;
    }

    /** The value stored in {@code builds.priority_class}; lower goes first. */
    public short rank() {
        return rank;
    }

    /** Empty for an unknown trigger, which is a malformed request rather than a reason to pick a default. */
    public static Optional<PriorityClass> of(String trigger) {
        return trigger == null ? Optional.empty() : Optional.ofNullable(BY_TRIGGER.get(trigger));
    }

    static PriorityClass fromRank(short rank) {
        return Arrays.stream(values())
                .filter(priority -> priority.rank == rank)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown priority class " + rank));
    }
}
