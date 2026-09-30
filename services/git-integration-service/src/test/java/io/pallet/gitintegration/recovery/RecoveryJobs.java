package io.pallet.gitintegration.recovery;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** The recovery jobs, run once from a test in another package, each from a reset cursor. */
public final class RecoveryJobs {

    private RecoveryJobs() {}

    /** @return pushes the reconciler found */
    public static int reconcile(ApplicationContext context) {
        resetCursors(context);
        return context.getBean(HeadReconciler.class).run().orElseThrow().pushesFound();
    }

    /** @return redeliveries the sweeper asked GitHub for */
    public static int sweep(ApplicationContext context) {
        resetCursors(context);
        return context.getBean(RedeliverySweeper.class).run().orElseThrow().requested();
    }

    public static void resetCursors(ApplicationContext context) {
        context.getBean(JdbcTemplate.class)
                .update(
                        "DELETE FROM git_integration.sync_cursors WHERE name IN (?, ?)",
                        HeadReconciler.CURSOR,
                        RedeliverySweeper.CURSOR);
    }
}
