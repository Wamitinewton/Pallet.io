package io.pallet.buildqueue.observability;

import static org.awaitility.Awaitility.await;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.cancel.CancellationService;
import io.pallet.buildqueue.dispatch.Dispatcher;
import io.pallet.buildqueue.lease.LeaseSweeps;
import io.pallet.buildqueue.support.PushEvents;
import io.pallet.buildqueue.support.StubBuilder;
import io.pallet.buildqueue.support.StubBuilder.Mode;
import io.pallet.common.events.BuildSucceeded;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Takes builds through every status with the real intake, dispatcher, lifecycle listener, cancellation, teardown and
 * lease sweeper, with the stub builder standing in for build-service: {@code REJECTED} and {@code CANCELLED} by org
 * deletion, {@code SUPERSEDED}, {@code CANCELLED} by a person, {@code DISPATCHED → RUNNING → SUCCEEDED}, {@code FAILED},
 * and {@code RUNNING → EXPIRED → SUCCEEDED} on a late result. Every push is shaped by the caller first.
 *
 * <p>Rounds and sweeps span every org, so the walk starts from an empty queue.
 */
final class EveryStatusWalk {

    /** Mapped to the {@code tight} profile in application-test.yml: three waiting builds, then rejections. */
    static final String FULL_ORG = "org-observability-full";

    private static final int FULL_ORG_MAX_QUEUED = 3;
    private static final String USER = "user-observability";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(50);

    /** The orgs, apps and builds the walk touched: none of them may appear in a metric label or a log line. */
    record Walked(List<String> orgIds, List<String> appIds, List<UUID> buildIds) {}

    private final PlatformEventPublisher publisher;
    private final BuildRepository builds;
    private final JdbcTemplate jdbc;
    private final Dispatcher dispatcher;
    private final CancellationService cancellation;
    private final StubBuilder stub;
    private final ApplicationContext context;

    private final List<String> orgIds = new ArrayList<>();
    private final List<String> appIds = new ArrayList<>();
    private final List<UUID> buildIds = new ArrayList<>();

    EveryStatusWalk(ApplicationContext context) {
        this.context = context;
        this.publisher = context.getBean(PlatformEventPublisher.class);
        this.builds = context.getBean(BuildRepository.class);
        this.jdbc = context.getBean(JdbcTemplate.class);
        this.dispatcher = context.getBean(Dispatcher.class);
        this.cancellation = context.getBean(CancellationService.class);
        this.stub = context.getBean(StubBuilder.class);
    }

    Walked walk(UnaryOperator<PushEvents.Builder> shape) {
        jdbc.update("DELETE FROM build_queue.builds");
        deleteRows(FULL_ORG);
        stub.start(Mode.normal());
        try {
            rejectedThenTornDown(shape);
            String orgId = "org-" + UUID.randomUUID();
            orgIds.add(orgId);
            supersededCancelledAndSucceeded(orgId, shape);
            failed(orgId, shape);
            expiredThenLateResult(orgId, shape);
        } finally {
            stub.stop();
        }
        return new Walked(List.copyOf(orgIds), List.copyOf(appIds), List.copyOf(buildIds));
    }

    void cleanUp() {
        orgIds.forEach(this::deleteRows);
        deleteRows(FULL_ORG);
    }

    private void rejectedThenTornDown(UnaryOperator<PushEvents.Builder> shape) {
        orgIds.add(FULL_ORG);
        List<GitPushReceived> pushes = new ArrayList<>();
        for (int i = 0; i <= FULL_ORG_MAX_QUEUED; i++) {
            pushes.add(push(FULL_ORG, newAppId(), shape));
        }
        pushes.forEach(publisher::publish);
        List<GitPushReceived> waiting = pushes.subList(0, FULL_ORG_MAX_QUEUED);
        waiting.forEach(push -> awaitStatus(push, BuildStatus.QUEUED));
        awaitStatus(pushes.getLast(), BuildStatus.REJECTED);

        publisher.publish(OrgDeleted.of(FULL_ORG, USER));
        waiting.forEach(push -> awaitStatus(push, BuildStatus.CANCELLED));
    }

    private void supersededCancelledAndSucceeded(String orgId, UnaryOperator<PushEvents.Builder> shape) {
        String appId = newAppId();
        GitPushReceived first = push(orgId, appId, shape);
        publisher.publish(first);
        awaitStatus(first, BuildStatus.QUEUED);
        GitPushReceived second = push(orgId, appId, shape);
        publisher.publish(second);
        awaitStatus(first, BuildStatus.SUPERSEDED);
        awaitStatus(second, BuildStatus.QUEUED);

        GitPushReceived cancelled = push(orgId, newAppId(), shape);
        publisher.publish(cancelled);
        awaitStatus(cancelled, BuildStatus.QUEUED);
        cancellation.cancelByUser(orgId, buildIdOf(cancelled), USER);

        dispatcher.runRound();
        awaitStatus(second, BuildStatus.SUCCEEDED);
    }

    private void failed(String orgId, UnaryOperator<PushEvents.Builder> shape) {
        stub.mode(Mode.fail());
        GitPushReceived push = push(orgId, newAppId(), shape);
        publisher.publish(push);
        awaitStatus(push, BuildStatus.QUEUED);
        dispatcher.runRound();
        awaitStatus(push, BuildStatus.FAILED);
    }

    /** The run lease is {@code build.max-duration + lease.grace} from application-test.yml, a few seconds. */
    private void expiredThenLateResult(String orgId, UnaryOperator<PushEvents.Builder> shape) {
        stub.mode(Mode.startOnly());
        GitPushReceived push = push(orgId, newAppId(), shape);
        publisher.publish(push);
        awaitStatus(push, BuildStatus.QUEUED);
        dispatcher.runRound();
        Build running = awaitStatus(push, BuildStatus.RUNNING);
        await().atMost(WAIT).pollInterval(POLL).until(() -> databaseNow().isAfter(running.leaseExpiresAt()));

        LeaseSweeps.sweep(context);
        Build expired = awaitStatus(push, BuildStatus.EXPIRED);
        publisher.publish(BuildSucceeded.of(
                orgId, expired.buildId().toString(), expired.appId(), expired.commitSha(), expired.pushEventId()));
        awaitStatus(push, BuildStatus.SUCCEEDED);
    }

    private GitPushReceived push(String orgId, String appId, UnaryOperator<PushEvents.Builder> shape) {
        GitPushReceived push = shape.apply(PushEvents.push(orgId, appId)).build();
        buildIds.add(buildIdOf(push));
        return push;
    }

    private String newAppId() {
        String appId = "app-" + UUID.randomUUID();
        appIds.add(appId);
        return appId;
    }

    private Build awaitStatus(GitPushReceived push, BuildStatus status) {
        return await().atMost(WAIT)
                .pollInterval(POLL)
                .until(
                        () -> builds.findByOrgIdAndBuildId(push.orgId(), buildIdOf(push)),
                        found -> found.map(Build::status).equals(Optional.of(status)))
                .orElseThrow();
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
    }

    private void deleteRows(String orgId) {
        jdbc.update("DELETE FROM build_queue.builds WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM build_queue.apps WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM build_queue.org_memberships WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM build_queue.orgs WHERE org_id = ?", orgId);
    }

    private static UUID buildIdOf(GitPushReceived push) {
        return BuildIds.buildId(push.eventId());
    }
}
