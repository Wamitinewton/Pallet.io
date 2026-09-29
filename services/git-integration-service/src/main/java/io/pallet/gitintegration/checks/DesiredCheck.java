package io.pallet.gitintegration.checks;

import java.util.Arrays;
import java.util.Objects;

/**
 * What a check run should show on GitHub. {@code conclusion} is set exactly when the state is {@code completed}.
 * {@code phase} orders two desires with the same state: a build finishing comes after it started, and a deploy after
 * both.
 */
public record DesiredCheck(CheckState state, Conclusion conclusion, String detailsUrl, String summary, Phase phase) {

    public enum Conclusion {
        SUCCESS("success"),
        FAILURE("failure");

        private final String wireName;

        Conclusion(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static Conclusion fromWireName(String wireName) {
            return Arrays.stream(values())
                    .filter(conclusion -> conclusion.wireName.equals(wireName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Unknown check run conclusion " + wireName));
        }
    }

    public enum Phase {
        BUILD_STARTED,
        BUILD_FINISHED,
        DEPLOY
    }

    public DesiredCheck {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(phase, "phase");
        if ((state == CheckState.COMPLETED) != (conclusion != null)) {
            throw new IllegalArgumentException("A conclusion is set exactly when the check is completed");
        }
    }

    /** The output title GitHub shows above the summary. */
    public String title() {
        return switch (state) {
            case QUEUED -> "Queued";
            case IN_PROGRESS -> "In progress";
            case COMPLETED -> conclusion == Conclusion.SUCCESS ? "Succeeded" : "Failed";
        };
    }
}
