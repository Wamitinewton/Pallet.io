package io.pallet.gitintegration.delivery;

import java.util.regex.Pattern;

public sealed interface DeliveryOutcome permits DeliveryOutcome.Processed, DeliveryOutcome.Ignored {

    DeliveryOutcome PROCESSED = new Processed();

    static DeliveryOutcome ignored(String reason) {
        return new Ignored(reason);
    }

    record Processed() implements DeliveryOutcome {}

    /** {@code reason} is stored in {@code outcome_reason} and used as a metric tag, so it is a fixed code. */
    record Ignored(String reason) implements DeliveryOutcome {

        private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

        public Ignored {
            if (reason == null || !CODE.matcher(reason).matches()) {
                throw new IllegalArgumentException("An ignore reason is an UPPER_SNAKE code of at most 32 characters");
            }
        }
    }
}
