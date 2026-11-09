package io.pallet.build.intake;

import io.pallet.build.admission.AdmissionSignal;
import io.pallet.build.config.BuildProperties;
import io.pallet.build.domain.Build;
import io.pallet.build.domain.BuildEvents;
import io.pallet.build.domain.BuildRepository;
import io.pallet.build.domain.BuildStatus;
import io.pallet.build.domain.BuildTransitions;
import io.pallet.build.domain.FailureCode;
import io.pallet.build.domain.NewBuild;
import io.pallet.build.domain.SettingsSnapshot;
import io.pallet.build.intake.IntakeMetrics.Outcome;
import io.pallet.build.projection.AppOrgMismatchException;
import io.pallet.build.projection.AppProjection;
import io.pallet.build.projection.AppProjectionRepository;
import io.pallet.build.projection.OrgProjection;
import io.pallet.build.projection.OrgProjectionRepository;
import io.pallet.build.settings.BuildSettingsRepository;
import io.pallet.common.outbox.OutboxWriter;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns every dispatch into exactly one build row. A build that hasn't been reported started takes a later attempt;
 * one that has ignores every later dispatch, so no build runs twice. Runs inside the listener's inbox transaction, so
 * the row, its event and admission's wakeup commit together or not at all.
 */
@Service
class BuildIntake {

    private static final String EVENT = "BuildDispatched";

    private static final Logger log = LoggerFactory.getLogger(BuildIntake.class);

    private final OrgProjectionRepository orgs;
    private final AppProjectionRepository apps;
    private final BuildSettingsRepository settings;
    private final BuildRepository builds;
    private final BuildTransitions transitions;
    private final BuildEvents events;
    private final OutboxWriter outbox;
    private final AdmissionSignal admission;
    private final IntakeMetrics metrics;
    private final Duration safetyMargin;

    BuildIntake(
            OrgProjectionRepository orgs,
            AppProjectionRepository apps,
            BuildSettingsRepository settings,
            BuildRepository builds,
            BuildTransitions transitions,
            BuildEvents events,
            OutboxWriter outbox,
            AdmissionSignal admission,
            IntakeMetrics metrics,
            BuildProperties properties) {
        this.orgs = orgs;
        this.apps = apps;
        this.settings = settings;
        this.builds = builds;
        this.transitions = transitions;
        this.events = events;
        this.outbox = outbox;
        this.admission = admission;
        this.metrics = metrics;
        this.safetyMargin = properties.launch().safetyMargin();
    }

    /**
     * @throws AppOrgMismatchException if the app is already known under another org
     * @throws DispatchConflictException if the build is already recorded for another org, app or commit
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(NewBuild build) {
        Optional<FailureCode> closed = closedReason(build);
        SettingsSnapshot snapshot = settings.snapshotOf(build.orgId(), build.appId());
        if (closed.isPresent()) {
            failAtIntake(build, snapshot, closed.get());
        } else {
            accept(build, snapshot);
        }
    }

    /**
     * Records the org and the app on first sight, then share-locks both, so a deletion committing concurrently is
     * either seen here or waits until this build is recorded and can then tear it down.
     */
    private Optional<FailureCode> closedReason(NewBuild build) {
        orgs.ensureExists(build.orgId());
        if (apps.insertActiveIfAbsent(build.orgId(), build.appId()) == 1) {
            log.info("App learned from a dispatch orgId={} appId={}", build.orgId(), build.appId());
        }
        OrgProjection org = orgs.lockShared(build.orgId())
                .orElseThrow(() -> new IllegalStateException("Org row missing after insert"));
        AppProjection app =
                apps.lockShared(build.orgId(), build.appId()).orElseThrow(() -> new AppOrgMismatchException(EVENT));
        if (org.status() == OrgProjection.Status.DELETED) {
            return Optional.of(FailureCode.ORG_DELETED);
        }
        if (app.status() == AppProjection.Status.DELETED) {
            return Optional.of(FailureCode.APP_DELETED);
        }
        return Optional.empty();
    }

    private void failAtIntake(NewBuild build, SettingsSnapshot snapshot, FailureCode code) {
        if (!transitions.failAtIntake(build, snapshot, code)) {
            requireSameBuild(build);
            record(build, Outcome.DUPLICATE, null);
            return;
        }
        outbox.append(events.failedAtIntake(build, code));
        record(build, Outcome.TORN_DOWN, BuildStatus.FAILED);
    }

    private void accept(NewBuild build, SettingsSnapshot snapshot) {
        Optional<BuildStatus> inserted = transitions.accept(build, snapshot, safetyMargin);
        if (inserted.isPresent()) {
            waiting(build, inserted.get(), Outcome.ACCEPTED);
            return;
        }
        Build stored = requireSameBuild(build);
        if (!stored.status().started() && stored.attempt() < build.attempt()) {
            BuildStatus refreshed = transitions
                    .refresh(build, safetyMargin)
                    .orElseThrow(() -> new IllegalStateException("Locked waiting build no longer refreshable"));
            waiting(build, refreshed, Outcome.REACCEPTED);
            return;
        }
        record(build, Outcome.DUPLICATE, stored.status());
    }

    private void waiting(NewBuild build, BuildStatus status, Outcome whenAccepted) {
        if (status == BuildStatus.ACCEPTED) {
            admission.wakeAdmission();
            record(build, whenAccepted, status);
        } else {
            record(build, Outcome.LATE, status);
        }
    }

    /** Locks the stored build and refuses a dispatch that names it for a different org, app or commit. */
    private Build requireSameBuild(NewBuild build) {
        Build stored = builds.lockByBuildId(build.buildId())
                .orElseThrow(() -> new IllegalStateException("Build row missing after a conflicting insert"));
        if (!stored.orgId().equals(build.orgId())) {
            throw new DispatchConflictException("orgId");
        }
        if (!stored.appId().equals(build.appId())) {
            throw new DispatchConflictException("appId");
        }
        if (!stored.commitSha().equals(build.commitSha())) {
            throw new DispatchConflictException("commitSha");
        }
        return stored;
    }

    private void record(NewBuild build, Outcome outcome, BuildStatus status) {
        metrics.dispatched(outcome);
        log.info(
                "Dispatch applied buildId={} orgId={} appId={} attempt={} outcome={} status={}",
                build.buildId(),
                build.orgId(),
                build.appId(),
                build.attempt(),
                outcome.tag(),
                status);
    }
}
