package io.pallet.buildqueue.cancel;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildEvents;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.build.BuildTransitions;
import io.pallet.buildqueue.projection.OrgProjectionRepository;
import io.pallet.buildqueue.security.AccessExceptions.BuildNotFoundException;
import io.pallet.buildqueue.security.AccessExceptions.OrgNotFoundException;
import io.pallet.buildqueue.security.ResourceScope;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.outbox.OutboxWriter;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person taking a waiting build out of the queue. The org row is locked and then the build row, by the guarded
 * update itself: the order intake and the dispatcher follow, so a cancel and a dispatch of the same build serialize and
 * exactly one of them wins.
 */
@Service
public class CancellationService {

    private final ResourceScope scope;
    private final OrgProjectionRepository orgs;
    private final BuildTransitions transitions;
    private final BuildEvents events;
    private final OutboxWriter outbox;

    CancellationService(
            ResourceScope scope,
            OrgProjectionRepository orgs,
            BuildTransitions transitions,
            BuildEvents events,
            OutboxWriter outbox) {
        this.scope = scope;
        this.orgs = orgs;
        this.transitions = transitions;
        this.events = events;
        this.outbox = outbox;
    }

    /**
     * Cancels the build if it is still {@code QUEUED}. Cancelling a build a person already cancelled returns it
     * unchanged, with no second event.
     *
     * @return the build as it stands after the cancellation
     * @throws BuildNotFoundException if the build is not one of the org's
     * @throws BuildNotCancellableException if the build has left the queue any other way
     */
    @Transactional
    public Build cancelByUser(String orgId, UUID buildId, String userId) {
        orgs.lockByOrgId(orgId).orElseThrow(OrgNotFoundException::new);
        boolean cancelled = transitions.cancel(orgId, buildId, BuildCancelled.REASON_USER_CANCELLED, userId);
        // Read only after the write: an entity loaded earlier in this transaction would not see it.
        Build build = scope.requireBuild(orgId, buildId);
        if (cancelled) {
            announce(build, userId);
            return build;
        }
        if (isUserCancelled(build)) {
            return build;
        }
        throw new BuildNotCancellableException(build.status());
    }

    private void announce(Build build, String userId) {
        outbox.append(events.cancelled(build, BuildCancelled.REASON_USER_CANCELLED, null));
        outbox.append(events.cancelledByUser(build, userId));
    }

    private static boolean isUserCancelled(Build build) {
        return build.status() == BuildStatus.CANCELLED
                && BuildCancelled.REASON_USER_CANCELLED.equals(build.statusReason());
    }
}
