package io.pallet.gitintegration.checks;

import java.util.Arrays;

/** A check run's status on GitHub, in the only order it may move: queued, then in progress, then completed. */
public enum CheckState {
    QUEUED("queued"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    private final String wireName;

    CheckState(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public int order() {
        return ordinal();
    }

    public boolean isAfter(CheckState other) {
        return order() > other.order();
    }

    static CheckState fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(state -> state.wireName.equals(wireName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown check run state " + wireName));
    }
}
