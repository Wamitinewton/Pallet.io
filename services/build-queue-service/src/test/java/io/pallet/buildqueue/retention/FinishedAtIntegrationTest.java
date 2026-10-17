package io.pallet.buildqueue.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.build.BuildTransitions;
import io.pallet.buildqueue.build.NewBuild;
import io.pallet.buildqueue.build.PriorityClass;
import io.pallet.buildqueue.support.ReadModelFixtures;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention finds terminal builds by {@code finished_at}, so every transition that ends a build must set it. Each path
 * is walked through {@link BuildTransitions} itself; a transition added later fails {@link #everyTransitionIsClassified}
 * until it is classified here and, if it ends a build, walked.
 */
@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class FinishedAtIntegrationTest {

    private static final Set<String> ENDING = Set.of(
            "reject",
            "supersede",
            "cancel",
            "cancelAllQueued",
            "succeed",
            "fail",
            "expireQueued",
            "expireUnstarted",
            "expireLeases");

    private static final Set<String> NOT_ENDING =
            Set.of("enqueue", "dispatch", "start", "redispatch", "extendDeadlines");

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final Duration START_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final int ALL = 10_000;

    @Autowired
    private BuildTransitions transitions;

    @Autowired
    private BuildRepository builds;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    private ReadModelFixtures fixtures;
    private String orgId;
    private String appId;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        orgId = fixtures.newOrg();
        appId = fixtures.newApp(orgId, "ACTIVE");
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void everyTransitionIsClassified() {
        Set<String> declared = Arrays.stream(BuildTransitions.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(declared).containsExactlyInAnyOrderElementsOf(union(ENDING, NOT_ENDING));
    }

    @Test
    void supersedingSetsIt() {
        UUID waiting = enqueued();
        NewBuild newer = newBuild();

        inTransaction(status -> {
            assertThat(transitions.supersede(orgId, waiting, newer.buildId())).isTrue();
            transitions.enqueue(newer);
        });

        assertFinished(waiting, BuildStatus.SUPERSEDED);
    }

    @Test
    void rejectingSetsIt() {
        NewBuild rejected = newBuild();

        inTransaction(status -> transitions.reject(rejected));

        assertFinished(rejected.buildId(), BuildStatus.REJECTED);
    }

    @Test
    void cancellingByAPersonSetsIt() {
        UUID buildId = enqueued();

        inTransaction(
                status -> assertThat(transitions.cancel(orgId, buildId, BuildCancelled.REASON_USER_CANCELLED, "user-1"))
                        .isTrue());

        assertFinished(buildId, BuildStatus.CANCELLED);
    }

    @Test
    void cancellingForADeletedAppSetsIt() {
        UUID buildId = enqueued();

        inTransaction(status -> transitions.cancelAllQueued(orgId, appId, BuildCancelled.REASON_APP_DELETED));

        assertFinished(buildId, BuildStatus.CANCELLED);
    }

    @Test
    void cancellingForADeletedOrgSetsIt() {
        UUID buildId = enqueued();

        inTransaction(status -> transitions.cancelAllQueued(orgId, BuildCancelled.REASON_ORG_DELETED));

        assertFinished(buildId, BuildStatus.CANCELLED);
    }

    @Test
    void succeedingWithoutAStartSetsIt() {
        UUID buildId = dispatched();

        inTransaction(
                status -> assertThat(transitions.succeed(orgId, appId, buildId)).isPresent());

        assertFinished(buildId, BuildStatus.SUCCEEDED);
    }

    @Test
    void succeedingAfterAStartSetsIt() {
        UUID buildId = running();

        inTransaction(
                status -> assertThat(transitions.succeed(orgId, appId, buildId)).isPresent());

        assertFinished(buildId, BuildStatus.SUCCEEDED);
    }

    @Test
    void failingSetsIt() {
        UUID buildId = running();

        inTransaction(status ->
                assertThat(transitions.fail(orgId, appId, buildId, "exit 1")).isPresent());

        assertFinished(buildId, BuildStatus.FAILED);
    }

    @Test
    void expiringAWaitingBuildSetsIt() {
        UUID buildId = enqueued();
        age("enqueued_at", buildId, Duration.ofDays(400));

        inTransaction(status -> transitions.expireQueued(Duration.ofDays(300), ALL));

        assertFinished(buildId, BuildStatus.EXPIRED);
    }

    @Test
    void expiringABuildThatNeverStartedSetsIt() {
        UUID buildId = dispatched();
        age("start_deadline", buildId, Duration.ofMinutes(1));

        inTransaction(status -> transitions.expireUnstarted(1, ALL));

        assertFinished(buildId, BuildStatus.EXPIRED);
    }

    @Test
    void expiringARunLeaseSetsIt() {
        UUID buildId = running();
        age("lease_expires_at", buildId, Duration.ofMinutes(1));

        inTransaction(status -> transitions.expireLeases(ALL));

        assertFinished(buildId, BuildStatus.EXPIRED);
    }

    @Test
    void aLateResultOnAnExpiredBuildMovesItLaterNeverClearsIt() {
        UUID buildId = running();
        age("lease_expires_at", buildId, Duration.ofMinutes(1));
        inTransaction(status -> transitions.expireLeases(ALL));
        Build expired = assertFinished(buildId, BuildStatus.EXPIRED);

        inTransaction(
                status -> assertThat(transitions.succeed(orgId, appId, buildId)).isPresent());

        assertThat(assertFinished(buildId, BuildStatus.SUCCEEDED).finishedAt()).isAfterOrEqualTo(expired.finishedAt());
    }

    private Build assertFinished(UUID buildId, BuildStatus status) {
        Build build = builds.findByOrgIdAndBuildId(orgId, buildId).orElseThrow();
        assertThat(build.status()).isEqualTo(status);
        assertThat(build.finishedAt()).as("finished_at after %s", status).isNotNull();
        return build;
    }

    private UUID enqueued() {
        NewBuild build = newBuild();
        inTransaction(status -> transitions.enqueue(build));
        return build.buildId();
    }

    private UUID dispatched() {
        UUID buildId = enqueued();
        inTransaction(status ->
                assertThat(transitions.dispatch(orgId, buildId, START_TIMEOUT)).isPresent());
        return buildId;
    }

    private UUID running() {
        UUID buildId = dispatched();
        inTransaction(status ->
                assertThat(transitions.start(orgId, appId, buildId, LEASE)).isPresent());
        return buildId;
    }

    private void age(String column, UUID buildId, Duration by) {
        jdbc.update(
                "UPDATE build_queue.builds SET %s = now() - ? * interval '1 millisecond' WHERE build_id = ?"
                        .formatted(column),
                by.toMillis(),
                buildId);
    }

    private NewBuild newBuild() {
        UUID buildId = UUID.randomUUID();
        return new NewBuild(
                buildId,
                orgId,
                appId,
                "main",
                SHA,
                UUID.randomUUID(),
                "WEBHOOK",
                PriorityClass.AUTOMATIC,
                "GITHUB",
                1,
                1,
                "octo-org/api",
                null,
                null,
                null,
                null);
    }

    private void inTransaction(Consumer<TransactionStatus> work) {
        transaction.executeWithoutResult(work);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        return Stream.concat(first.stream(), second.stream()).collect(Collectors.toSet());
    }
}
