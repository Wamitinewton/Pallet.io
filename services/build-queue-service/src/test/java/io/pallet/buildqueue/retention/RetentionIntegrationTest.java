package io.pallet.buildqueue.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.config.SchedulingLocks;
import io.pallet.buildqueue.config.SchedulingLocks.Job;
import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.buildqueue.retention.RetentionSweeps.Run;
import io.pallet.buildqueue.retention.RetentionSweeps.Step;
import io.pallet.buildqueue.retention.RetentionSweeps.StepRun;
import io.pallet.buildqueue.support.Counters;
import io.pallet.buildqueue.support.ReadModelFixtures;
import io.pallet.common.inbox.InboxProperties;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.outbox.OutboxProperties;
import io.pallet.common.outbox.OutboxRepository;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs retention by hand; {@code retention.enabled} is off in every test context, so nothing else sweeps. */
@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class RetentionIntegrationTest {

    private static final Duration PAST_RETENTION = Duration.ofDays(31);
    private static final Duration RECENT = Duration.ofDays(1);

    private static final EnumSet<BuildStatus> FINISHED = EnumSet.of(
            BuildStatus.SUCCEEDED,
            BuildStatus.FAILED,
            BuildStatus.SUPERSEDED,
            BuildStatus.CANCELLED,
            BuildStatus.REJECTED,
            BuildStatus.EXPIRED);

    @Autowired
    private RetentionSweeps sweeps;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext context;

    private ReadModelFixtures fixtures;
    private RetentionRows rows;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        rows = new RetentionRows(jdbc);
        drain();
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void oldTerminalBuildsOfEveryStatusAreDeletedWhileRecentAndUnfinishedOnesStay() {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "ACTIVE");
        Map<BuildStatus, UUID> old = FINISHED.stream()
                .collect(Collectors.toMap(
                        Function.identity(), status -> rows.finished(orgId, appId, status, PAST_RETENTION)));
        Map<BuildStatus, UUID> recent = FINISHED.stream()
                .collect(Collectors.toMap(Function.identity(), status -> rows.finished(orgId, appId, status, RECENT)));
        UUID queued = rows.queued(orgId, appId, Duration.ofDays(400));
        UUID running = rows.running(orgId, fixtures.newApp(orgId, "ACTIVE"), Duration.ofDays(400));
        UUID dispatched = rows.dispatched(orgId, fixtures.newApp(orgId, "ACTIVE"), Duration.ofDays(400));
        double deletedBefore = Counters.count(QueueMetrics.RETENTION_DELETED, QueueMetrics.TAG_TABLE, "builds");

        Run run = sweeps.run().orElseThrow();

        old.values().forEach(buildId -> assertThat(rows.buildExists(buildId)).isFalse());
        recent.values().forEach(buildId -> assertThat(rows.buildExists(buildId)).isTrue());
        assertThat(rows.buildExists(queued)).isTrue();
        assertThat(rows.buildExists(running)).isTrue();
        assertThat(rows.buildExists(dispatched)).isTrue();
        assertThat(run.step(Step.TERMINAL_BUILDS))
                .satisfies(step -> assertThat(step.deleted()).isEqualTo(FINISHED.size()))
                .satisfies(step -> assertThat(step.complete()).isTrue());
        assertThat(Counters.count(QueueMetrics.RETENTION_DELETED, QueueMetrics.TAG_TABLE, "builds") - deletedBefore)
                .isEqualTo(FINISHED.size());
    }

    @Test
    void aBuildSupersededByAPurgedBuildKeepsItsRowWithTheReferenceNulled() {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "ACTIVE");
        UUID purged = rows.finished(orgId, appId, BuildStatus.SUCCEEDED, PAST_RETENTION);
        UUID kept = rows.finished(orgId, appId, BuildStatus.SUPERSEDED, RECENT);
        rows.pointSupersededBy(kept, purged);

        sweeps.run().orElseThrow();

        assertThat(rows.buildExists(purged)).isFalse();
        assertThat(rows.buildExists(kept)).isTrue();
        assertThat(rows.supersededBy(kept)).isNull();
    }

    @Test
    void aDeletedAppIsPurgedOnlyOnceItsWindowHasPassedAndNoBuildRemains() {
        String orgId = fixtures.newOrg();
        String bare = fixtures.newApp(orgId, "ACTIVE");
        rows.appDeletedAgo(bare, PAST_RETENTION);
        String withBuild = fixtures.newApp(orgId, "ACTIVE");
        rows.appDeletedAgo(withBuild, PAST_RETENTION);
        rows.finished(orgId, withBuild, BuildStatus.CANCELLED, RECENT);
        String freshlyDeleted = fixtures.newApp(orgId, "ACTIVE");
        rows.appDeletedAgo(freshlyDeleted, RECENT);
        String active = fixtures.newApp(orgId, "ACTIVE");
        rows.touchedAgo("apps", "app_id", active, Duration.ofDays(400));

        sweeps.run().orElseThrow();

        assertThat(rows.appExists(bare)).isFalse();
        assertThat(rows.appExists(withBuild)).isTrue();
        assertThat(rows.appExists(freshlyDeleted)).isTrue();
        assertThat(rows.appExists(active)).isTrue();
    }

    @Test
    void aDeletedOrgWithNothingLeftIsPurgedWithItsMemberships() {
        String orgId = fixtures.newOrg();
        fixtures.newMember(orgId, "OWNER", "REMOVED");
        fixtures.newMember(orgId, "DEVELOPER", "REMOVED");
        rows.orgDeletedAgo(orgId, PAST_RETENTION);
        double membershipsBefore =
                Counters.count(QueueMetrics.RETENTION_DELETED, QueueMetrics.TAG_TABLE, "org_memberships");

        sweeps.run().orElseThrow();

        assertThat(rows.orgExists(orgId)).isFalse();
        assertThat(rows.membershipCount(orgId)).isZero();
        assertThat(Counters.count(QueueMetrics.RETENTION_DELETED, QueueMetrics.TAG_TABLE, "org_memberships")
                        - membershipsBefore)
                .isEqualTo(2);
    }

    @Test
    void aDeletedOrgKeepsItsTombstoneWhileAnythingStillHangsOffIt() {
        String withApp = fixtures.newOrg();
        fixtures.newApp(withApp, "ACTIVE");
        rows.orgDeletedAgo(withApp, PAST_RETENTION);
        String withActiveMember = fixtures.newOrg();
        fixtures.newMember(withActiveMember, "OWNER", "ACTIVE");
        rows.orgDeletedAgo(withActiveMember, PAST_RETENTION);
        String freshlyDeleted = fixtures.newOrg();
        rows.orgDeletedAgo(freshlyDeleted, RECENT);
        String active = fixtures.newOrg();
        rows.touchedAgo("orgs", "org_id", active, Duration.ofDays(400));

        sweeps.run().orElseThrow();

        assertThat(rows.orgExists(withApp)).isTrue();
        assertThat(rows.orgExists(withActiveMember)).isTrue();
        assertThat(rows.membershipCount(withActiveMember)).isEqualTo(1);
        assertThat(rows.orgExists(freshlyDeleted)).isTrue();
        assertThat(rows.orgExists(active)).isTrue();
    }

    @Test
    void oneRunTakesAnOrgsLastBuildsThenItsAppThenTheOrgItself() {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "ACTIVE");
        UUID buildId = rows.finished(orgId, appId, BuildStatus.CANCELLED, PAST_RETENTION);
        rows.appDeletedAgo(appId, PAST_RETENTION);
        fixtures.newMember(orgId, "OWNER", "REMOVED");
        rows.orgDeletedAgo(orgId, PAST_RETENTION);

        sweeps.run().orElseThrow();

        assertThat(rows.buildExists(buildId)).isFalse();
        assertThat(rows.appExists(appId)).isFalse();
        assertThat(rows.orgExists(orgId)).isFalse();
        assertThat(rows.membershipCount(orgId)).isZero();
    }

    @Test
    void publishedOutboxRowsAndProcessedInboxRowsGoOnTheSharedWindows() {
        String orgId = fixtures.newOrg();
        UUID published = rows.outboxEvent(orgId, "PUBLISHED", Duration.ofDays(8));
        UUID recentlyPublished = rows.outboxEvent(orgId, "PUBLISHED", RECENT);
        UUID parked = rows.outboxEvent(orgId, "PARKED", Duration.ofDays(8));
        UUID processed = rows.processedEvent(Duration.ofDays(15));
        UUID recentlyProcessed = rows.processedEvent(RECENT);

        try {
            sweeps.run().orElseThrow();

            assertThat(rows.outboxEventExists(published)).isFalse();
            assertThat(rows.outboxEventExists(recentlyPublished)).isTrue();
            assertThat(rows.outboxEventExists(parked)).isTrue();
            assertThat(rows.processedEventExists(processed)).isFalse();
            assertThat(rows.processedEventExists(recentlyProcessed)).isTrue();
        } finally {
            rows.removeOutboxEvents(orgId);
        }
    }

    @Test
    void aBacklogWithinTheBatchBudgetIsDeletedInOneRun() {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "ACTIVE");
        rows.finished(orgId, appId, 2_500, PAST_RETENTION);

        StepRun run = sweepsWithBatchSize(500).run().orElseThrow().step(Step.TERMINAL_BUILDS);

        assertThat(run.deleted()).isEqualTo(2_500);
        assertThat(run.batches()).isEqualTo(6);
        assertThat(run.complete()).isTrue();
        assertThat(rows.buildCount(appId)).isZero();
    }

    @Test
    void aBacklogBeyondTheBatchBudgetStopsAtItAndFinishesOnTheNextRun() {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "ACTIVE");
        rows.finished(orgId, appId, 1_500, PAST_RETENTION);
        RetentionSweeps small = sweepsWithBatchSize(100);

        StepRun first = small.run().orElseThrow().step(Step.TERMINAL_BUILDS);

        assertThat(first.deleted()).isEqualTo(1_000);
        assertThat(first.batches()).isEqualTo(10);
        assertThat(first.complete()).isFalse();
        assertThat(rows.buildCount(appId)).isEqualTo(500);

        StepRun second = small.run().orElseThrow().step(Step.TERMINAL_BUILDS);

        assertThat(second.deleted()).isEqualTo(500);
        assertThat(second.complete()).isTrue();
        assertThat(rows.buildCount(appId)).isZero();
    }

    @Test
    void whileAnotherInstanceHoldsTheLockARunDoesNothing() throws SQLException {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "ACTIVE");
        UUID buildId = rows.finished(orgId, appId, BuildStatus.SUCCEEDED, PAST_RETENTION);

        try (Connection otherInstance = holdRetentionLock()) {
            assertThat(sweeps.run()).isEmpty();
            assertThat(rows.buildExists(buildId)).isTrue();
            otherInstance.rollback();
        }

        assertThat(sweeps.run()).isPresent();
        assertThat(rows.buildExists(buildId)).isFalse();
    }

    @Test
    void aBuildAnotherTransactionHoldsIsSkippedWithoutWaitingAndTakenNextRun() throws SQLException {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "ACTIVE");
        UUID held = rows.finished(orgId, appId, BuildStatus.FAILED, PAST_RETENTION);
        UUID free = rows.finished(orgId, appId, BuildStatus.FAILED, PAST_RETENTION);

        try (Connection listener = outsideThePool()) {
            try (PreparedStatement lock =
                    listener.prepareStatement("SELECT 1 FROM build_queue.builds WHERE build_id = ? FOR UPDATE")) {
                lock.setObject(1, held);
                lock.executeQuery().close();
            }
            long started = System.nanoTime();

            Run run = sweeps.run().orElseThrow();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(run.step(Step.TERMINAL_BUILDS).complete()).isTrue();
            assertThat(rows.buildExists(free)).isFalse();
            assertThat(rows.buildExists(held)).isTrue();
            listener.rollback();
        }

        sweeps.run().orElseThrow();

        assertThat(rows.buildExists(held)).isFalse();
    }

    /** Clears anything another test class left past its window, so counts here are this test's alone. */
    private void drain() {
        boolean complete;
        do {
            complete = sweeps.run().orElseThrow().steps().stream().allMatch(StepRun::complete);
        } while (!complete);
    }

    /** As an instance configured with {@code retention.batch-size}, sharing this context's database and lock. */
    private RetentionSweeps sweepsWithBatchSize(int batchSize) {
        BuildQueueProperties properties = context.getBean(BuildQueueProperties.class);
        BuildQueueProperties.Retention retention = properties.retention();
        return new RetentionSweeps(
                context.getBean(SchedulingLocks.class),
                context.getBean(RetentionRepository.class),
                context.getBean(OutboxRepository.class),
                context.getBean(TransactionalInbox.class),
                context.getBean(QueueMetrics.class),
                context.getBean(TransactionTemplate.class),
                new BuildQueueProperties(
                        properties.dispatch(),
                        properties.build(),
                        properties.lease(),
                        properties.queue(),
                        properties.limits(),
                        new BuildQueueProperties.Retention(
                                retention.terminalBuilds(),
                                retention.tombstones(),
                                batchSize,
                                retention.maxBatchesPerRun(),
                                retention.interval(),
                                retention.enabled()),
                        properties.observability()),
                context.getBean(OutboxProperties.class),
                context.getBean(InboxProperties.class));
    }

    private Connection holdRetentionLock() throws SQLException {
        Connection connection = outsideThePool();
        try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            lock.setLong(1, Job.RETENTION.key());
            lock.executeQuery().close();
        }
        return connection;
    }

    /** A connection of its own, so holding it never starves the run it is racing of a pooled one. */
    private Connection outsideThePool() throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        Connection connection = DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        connection.setAutoCommit(false);
        return connection;
    }
}
