package io.pallet.buildqueue.build;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.BuildDispatched;
import io.pallet.common.events.BuildQueued;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The only place build events are made. Event ids come from {@link BuildIds}. */
@Component
public class BuildEvents {

    public static final String AUDIT_BUILD_CANCELLED = "build.cancelled";

    private final Clock clock;

    BuildEvents(Clock clock) {
        this.clock = clock;
    }

    public BuildQueued queued(NewBuild build, UUID supersedes) {
        return BuildQueued.of(
                BuildIds.queuedEventId(build.buildId()),
                build.orgId(),
                clock.instant(),
                build.buildId().toString(),
                build.appId(),
                build.branch(),
                build.commitSha(),
                build.pushEventId(),
                build.trigger(),
                idOrNull(supersedes));
    }

    /**
     * Everything build-service needs to run the attempt and nothing secret; {@code startBy} is the stored deadline. A
     * redispatch is the same build with a later attempt, so its event id differs only by the attempt.
     */
    public BuildDispatched dispatched(DispatchedBuild build, Duration maxDuration) {
        return BuildDispatched.of(
                BuildIds.dispatchedEventId(build.buildId(), build.attempt()),
                build.orgId(),
                clock.instant(),
                new BuildDispatched.Dispatch(
                        build.buildId().toString(),
                        build.attempt(),
                        build.appId(),
                        build.branch(),
                        build.commitSha(),
                        build.pushEventId(),
                        build.trigger(),
                        build.provider(),
                        build.installationId(),
                        build.repoId(),
                        build.repo(),
                        build.rootDirectory(),
                        build.startDeadline(),
                        maxDuration.toSeconds()));
    }

    /** A request that never entered the queue because its org's backlog was full. */
    public BuildCancelled rejected(NewBuild build) {
        return cancelled(
                build.buildId(),
                build.orgId(),
                build.appId(),
                build.commitSha(),
                build.pushEventId(),
                BuildCancelled.REASON_QUEUE_FULL,
                null);
    }

    public BuildCancelled cancelled(Build build, String reason, UUID supersededBy) {
        return cancelled(
                build.buildId(),
                build.orgId(),
                build.appId(),
                build.commitSha(),
                build.pushEventId(),
                reason,
                supersededBy);
    }

    public BuildCancelled cancelled(CancelledBuild build) {
        return cancelled(
                build.buildId(),
                build.orgId(),
                build.appId(),
                build.commitSha(),
                build.pushEventId(),
                build.reason(),
                null);
    }

    /** Who took the build out of the queue. Ids only: the commit's message and author are untrusted text. */
    public AuditEventRecorded cancelledByUser(Build build, String userId) {
        return new AuditEventRecorded(
                BuildIds.cancellationAuditEventId(build.buildId()),
                AuditEventRecorded.TYPE,
                build.orgId(),
                clock.instant(),
                userId,
                AUDIT_BUILD_CANCELLED,
                "build:" + build.buildId(),
                Map.of("buildId", build.buildId().toString(), "appId", build.appId()));
    }

    /** The queue stopped waiting for the build; {@code reason} says which deadline passed. */
    public BuildCancelled expired(ExpiredBuild build) {
        return cancelled(
                build.buildId(),
                build.orgId(),
                build.appId(),
                build.commitSha(),
                build.pushEventId(),
                build.reason(),
                null);
    }

    private BuildCancelled cancelled(
            UUID buildId,
            String orgId,
            String appId,
            String commitSha,
            UUID pushEventId,
            String reason,
            UUID supersededBy) {
        return BuildCancelled.of(
                BuildIds.cancelledEventId(buildId),
                orgId,
                clock.instant(),
                buildId.toString(),
                appId,
                commitSha,
                pushEventId,
                reason,
                idOrNull(supersededBy));
    }

    private static String idOrNull(UUID id) {
        return id == null ? null : id.toString();
    }
}
