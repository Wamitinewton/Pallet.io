package io.pallet.build.domain;

import java.util.EnumSet;
import java.util.Set;

/** A build's lifecycle. Status only moves forward; every change is a guarded update in {@link BuildTransitions}. */
public enum BuildStatus {
    ACCEPTED,
    DROPPED,
    STARTING,
    RUNNING,
    SUCCEEDED,
    FAILED;

    /** The statuses a later dispatch can still refresh: neither has been reported started. */
    public static final Set<BuildStatus> WAITING = EnumSet.of(ACCEPTED, DROPPED);

    public boolean started() {
        return !WAITING.contains(this);
    }

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
