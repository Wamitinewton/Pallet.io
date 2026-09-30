package io.pallet.gitintegration.checks;

import org.springframework.context.ApplicationContext;

/** One check run report cycle, run from a test in another package. */
public final class CheckRunJobs {

    private CheckRunJobs() {}

    /** @return how many check runs the cycle claimed */
    public static int report(ApplicationContext context) {
        return context.getBean(CheckRunReporter.class).report().orElseThrow();
    }

    public static String spanName() {
        return CheckRunReporter.SPAN_NAME;
    }
}
