package io.pallet.build.domain;

import io.pallet.common.events.BuildFailed;

/** The three steps every build runs, in order; the unit a failure is attributed to. */
public enum BuildStep {
    FETCH(BuildFailed.STEP_FETCH),
    BUILD(BuildFailed.STEP_BUILD),
    PUSH(BuildFailed.STEP_PUSH);

    private final String wireName;

    BuildStep(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
