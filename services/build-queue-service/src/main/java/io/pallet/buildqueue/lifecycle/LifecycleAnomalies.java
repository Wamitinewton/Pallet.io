package io.pallet.buildqueue.lifecycle;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.buildqueue.observability.QueueMetrics.Anomaly;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts for build-service reports that fit no ordinary transition. Each is still a valid event: it is classified,
 * counted and acknowledged, and nothing here throws.
 */
@Component
class LifecycleAnomalies {

    /** {@code alerting} kinds mean build-service broke its contract (ARCHITECTURE.md §Build lifecycle). */
    enum Kind {
        UNTRACKED(Anomaly.UNTRACKED, false),
        OUT_OF_ORDER(Anomaly.OUT_OF_ORDER, false),
        LATE_START(Anomaly.LATE_START, true),
        LATE_RESULT(Anomaly.LATE_RESULT, false),
        IMPOSSIBLE(Anomaly.IMPOSSIBLE, true);

        private final Anomaly metric;
        private final boolean alerting;

        Kind(Anomaly metric, boolean alerting) {
            this.metric = metric;
            this.alerting = alerting;
        }

        Anomaly metric() {
            return metric;
        }

        Level logLevel() {
            return alerting ? Level.WARN : Level.INFO;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(LifecycleAnomalies.class);

    private final BuildRepository builds;
    private final QueueMetrics metrics;

    LifecycleAnomalies(BuildRepository builds, QueueMetrics metrics) {
        this.builds = builds;
        this.metrics = metrics;
    }

    /** Works out why a report's guarded update matched no row, from the row as it is now. */
    @Transactional(propagation = Propagation.MANDATORY)
    Kind unmatched(BuildReport report) {
        Optional<Build> build = builds.findByOrgIdAndBuildId(report.orgId(), report.buildId());
        if (build.isEmpty()) {
            builds.findOwningOrgId(report.buildId())
                    .ifPresent(owner -> log.warn(
                            "Build report names another org's build buildId={} reportedOrgId={} owningOrgId={}"
                                    + " eventId={} type={}",
                            report.buildId(),
                            report.orgId(),
                            owner,
                            report.eventId(),
                            report.type()));
            return record(Kind.UNTRACKED, report, null);
        }
        if (!build.get().appId().equals(report.appId())) {
            log.warn(
                    "Build report names another app's build buildId={} orgId={} reportedAppId={} owningAppId={}"
                            + " eventId={} type={}",
                    report.buildId(),
                    report.orgId(),
                    report.appId(),
                    build.get().appId(),
                    report.eventId(),
                    report.type());
            return record(Kind.UNTRACKED, report, build.get().status());
        }
        BuildStatus status = build.get().status();
        return record(classify(report.type(), status), report, status);
    }

    /** A result that moved an {@code EXPIRED} build on: applied, since the build really ran, and counted. */
    void lateResult(BuildReport report) {
        record(Kind.LATE_RESULT, report, BuildStatus.EXPIRED);
    }

    /**
     * Statuses a report's own transition accepts can't reach here, since its guarded update would have matched; they
     * are {@code IMPOSSIBLE} like every status a build-service report should never meet.
     */
    static Kind classify(BuildReport.Type type, BuildStatus status) {
        return switch (status) {
            case SUCCEEDED, FAILED -> Kind.OUT_OF_ORDER;
            case RUNNING -> type.isResult() ? Kind.IMPOSSIBLE : Kind.OUT_OF_ORDER;
            case EXPIRED -> type.isResult() ? Kind.IMPOSSIBLE : Kind.LATE_START;
            case QUEUED, DISPATCHED, SUPERSEDED, CANCELLED, REJECTED -> Kind.IMPOSSIBLE;
        };
    }

    private Kind record(Kind kind, BuildReport report, BuildStatus status) {
        metrics.anomaly(kind.metric());
        log.atLevel(kind.logLevel())
                .log(
                        "Build report anomaly kind={} buildId={} orgId={} appId={} status={} eventId={} type={}",
                        kind,
                        report.buildId(),
                        report.orgId(),
                        report.appId(),
                        status,
                        report.eventId(),
                        report.type());
        return kind;
    }
}
