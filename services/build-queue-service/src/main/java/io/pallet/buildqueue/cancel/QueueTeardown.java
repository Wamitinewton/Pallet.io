package io.pallet.buildqueue.cancel;

import io.pallet.buildqueue.build.BuildEvents;
import io.pallet.buildqueue.build.BuildTransitions;
import io.pallet.buildqueue.build.CancelledBuild;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.outbox.OutboxWriter;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels the waiting builds of a deleted app or org, each with its own {@code build.cancelled}. Builds already in
 * flight are left to finish or expire: the queue can't stop them. Coalescing bounds an app's waiting builds to one per
 * branch and the queue cap bounds an org's, so one statement covers them all.
 *
 * <p>Call inside the deletion's inbox transaction, with the org row locked and the tombstone written, so no push can
 * be admitted between the two.
 */
@Component
public class QueueTeardown {

    private final BuildTransitions transitions;
    private final BuildEvents events;
    private final OutboxWriter outbox;

    QueueTeardown(BuildTransitions transitions, BuildEvents events, OutboxWriter outbox) {
        this.transitions = transitions;
        this.events = events;
        this.outbox = outbox;
    }

    /** @return how many builds were cancelled */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelQueuedForApp(String orgId, String appId) {
        return announce(transitions.cancelAllQueued(orgId, appId, BuildCancelled.REASON_APP_DELETED));
    }

    /** @return how many builds were cancelled */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelQueuedForOrg(String orgId) {
        return announce(transitions.cancelAllQueued(orgId, BuildCancelled.REASON_ORG_DELETED));
    }

    private int announce(List<CancelledBuild> cancelled) {
        for (CancelledBuild build : cancelled) {
            outbox.append(events.cancelled(build));
        }
        return cancelled.size();
    }
}
