package io.pallet.buildqueue.intake;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildEvents;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildTransitions;
import io.pallet.buildqueue.build.NewBuild;
import io.pallet.buildqueue.dispatch.DispatchSignal;
import io.pallet.buildqueue.dispatch.LimitProfiles;
import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.buildqueue.observability.QueueMetrics.DropReason;
import io.pallet.buildqueue.observability.QueueMetrics.RequestOutcome;
import io.pallet.buildqueue.projection.AppOrgMismatchException;
import io.pallet.buildqueue.projection.AppProjection;
import io.pallet.buildqueue.projection.AppProjectionRepository;
import io.pallet.buildqueue.projection.OrgProjection;
import io.pallet.buildqueue.projection.OrgProjectionRepository;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.outbox.OutboxWriter;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a build request into exactly one build row, or drops it against a deleted app or org. Runs inside the
 * listener's inbox transaction, so the rows, their events and the dispatcher's wakeup commit together or not at all.
 * Requests are applied in partition order; nothing here compares commits or timestamps.
 */
@Service
class BuildIntake {

    private static final String EVENT = "GitPushReceived";

    private static final Logger log = LoggerFactory.getLogger(BuildIntake.class);

    private final OrgProjectionRepository orgs;
    private final AppProjectionRepository apps;
    private final BuildRepository builds;
    private final BuildTransitions transitions;
    private final BuildEvents events;
    private final OutboxWriter outbox;
    private final LimitProfiles limits;
    private final DispatchSignal dispatchSignal;
    private final QueueMetrics metrics;

    BuildIntake(
            OrgProjectionRepository orgs,
            AppProjectionRepository apps,
            BuildRepository builds,
            BuildTransitions transitions,
            BuildEvents events,
            OutboxWriter outbox,
            LimitProfiles limits,
            DispatchSignal dispatchSignal,
            QueueMetrics metrics) {
        this.orgs = orgs;
        this.apps = apps;
        this.builds = builds;
        this.transitions = transitions;
        this.events = events;
        this.outbox = outbox;
        this.limits = limits;
        this.dispatchSignal = dispatchSignal;
        this.metrics = metrics;
    }

    /** @throws AppOrgMismatchException if the app is already known under another org */
    @Transactional(propagation = Propagation.MANDATORY)
    public void accept(NewBuild build) {
        if (!orgAccepts(build) || !appAccepts(build)) {
            return;
        }
        if (builds.existsByOrgIdAndBuildId(build.orgId(), build.buildId())) {
            log.info(
                    "Build already recorded buildId={} orgId={} pushEventId={}",
                    build.buildId(),
                    build.orgId(),
                    build.pushEventId());
            return;
        }
        Optional<Build> waiting = builds.lockQueued(build.orgId(), build.appId(), build.branch());
        waiting.ifPresent(replaced -> supersede(replaced, build));
        if (waiting.isEmpty() && atQueueCap(build.orgId())) {
            reject(build);
            return;
        }
        enqueue(build, waiting.map(Build::buildId).orElse(null));
    }

    /** Locks the org row first, the lock order every writer of an org's builds follows. */
    private boolean orgAccepts(NewBuild build) {
        orgs.ensureExists(build.orgId());
        OrgProjection org = orgs.lockByOrgId(build.orgId())
                .orElseThrow(() -> new IllegalStateException("Org row missing after insert"));
        if (org.status() == OrgProjection.Status.DELETED) {
            drop(build, DropReason.ORG_DELETED);
            return false;
        }
        return true;
    }

    /**
     * An app this service hasn't seen yet is recorded under the push's org: git-integration-service only publishes for
     * apps it learned from {@code app.created}, so the projection here is just behind.
     */
    private boolean appAccepts(NewBuild build) {
        if (apps.insertActiveIfAbsent(build.orgId(), build.appId(), null) == 1) {
            log.info("App learned from a push orgId={} appId={}", build.orgId(), build.appId());
            return true;
        }
        AppProjection app = apps.findByOrgIdAndAppId(build.orgId(), build.appId())
                .orElseThrow(() -> new AppOrgMismatchException(EVENT));
        if (app.status() == AppProjection.Status.DELETED) {
            drop(build, DropReason.APP_DELETED);
            return false;
        }
        return true;
    }

    private void supersede(Build replaced, NewBuild replacement) {
        if (!transitions.supersede(replaced.orgId(), replaced.buildId(), replacement.buildId())) {
            throw new IllegalStateException("Locked waiting build " + replaced.buildId() + " is no longer QUEUED");
        }
        outbox.append(events.cancelled(replaced, BuildCancelled.REASON_SUPERSEDED, replacement.buildId()));
    }

    private boolean atQueueCap(String orgId) {
        return builds.countQueued(orgId) >= limits.forOrg(orgId).maxQueued();
    }

    private void reject(NewBuild build) {
        transitions.reject(build);
        outbox.append(events.rejected(build));
        metrics.requested(RequestOutcome.REJECTED, build.trigger());
    }

    private void enqueue(NewBuild build, UUID supersedes) {
        transitions.enqueue(build);
        outbox.append(events.queued(build, supersedes));
        dispatchSignal.notifyDispatcher();
        metrics.requested(RequestOutcome.QUEUED, build.trigger());
    }

    private void drop(NewBuild build, DropReason reason) {
        metrics.dropped(reason);
        log.info(
                "Push dropped orgId={} appId={} pushEventId={} reason={}",
                build.orgId(),
                build.appId(),
                build.pushEventId(),
                reason);
    }
}
