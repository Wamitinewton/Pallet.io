package io.pallet.gitintegration.access;

import org.springframework.context.ApplicationContext;

/** Access re-verification, run once from a test in another package. */
public final class ReverifyJobs {

    private ReverifyJobs() {}

    /** @return how many links the run checked against GitHub */
    public static int run(ApplicationContext context) {
        return context.getBean(AccessReverifier.class).run().orElseThrow().checked().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }
}
