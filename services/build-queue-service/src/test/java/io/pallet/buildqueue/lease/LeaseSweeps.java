package io.pallet.buildqueue.lease;

import org.springframework.context.ApplicationContext;

/** Runs one lease sweep from a test outside this package, as the schedule would. */
public final class LeaseSweeps {

    private LeaseSweeps() {}

    public static void sweep(ApplicationContext context) {
        context.getBean(LeaseSweeper.class).sweep();
    }
}
