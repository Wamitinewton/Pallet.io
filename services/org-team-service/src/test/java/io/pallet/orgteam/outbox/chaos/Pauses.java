package io.pallet.orgteam.outbox.chaos;

import java.time.Duration;

final class Pauses {

    private Pauses() {}

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while pausing", e);
        }
    }
}
