package io.pallet.gitintegration.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.BuildFailed;
import io.pallet.common.events.BuildStarted;
import io.pallet.common.events.BuildSucceeded;
import io.pallet.common.events.DeployStateChanged;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.checks.DesiredCheck.Conclusion;
import io.pallet.gitintegration.observability.MetricsCatalog;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.TopicProbe;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class CheckEventListenersIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private CheckRunRepository checkRuns;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private KafkaContainer kafka;

    private ReadModelFixtures fixtures;
    private String orgId;
    private UUID appId;
    private String sha;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        orgId = fixtures.newOrg();
        long installationId = fixtures.newInstallation();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        appId = fixtures.newRepoLink(
                orgId, installationId, ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        sha = newSha();
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void aStartedBuildIsInProgress() {
        publisher.publish(BuildStarted.of(orgId, "bld-1", appId.toString(), sha, UUID.randomUUID()));

        CheckRun row = awaitRevision(sha, 1);
        assertThat(row.desired().state()).isEqualTo(CheckState.IN_PROGRESS);
        assertThat(row.desired().summary()).isEqualTo("Building");
        assertThat(row.orgId()).isEqualTo(orgId);
        assertThat(row.pending()).isTrue();
        assertThat(row.checkRunId()).isNull();
    }

    @Test
    void aSucceededBuildCompletesTheCheck() {
        publisher.publish(BuildStarted.of(orgId, "bld-1", appId.toString(), sha, UUID.randomUUID()));
        awaitRevision(sha, 1);

        publisher.publish(BuildSucceeded.of(orgId, "bld-1", appId.toString(), sha, UUID.randomUUID()));

        CheckRun row = awaitRevision(sha, 2);
        assertThat(row.desired().state()).isEqualTo(CheckState.COMPLETED);
        assertThat(row.desired().conclusion()).isEqualTo(Conclusion.SUCCESS);
        assertThat(row.attempts()).isZero();
    }

    @Test
    void aFailedBuildCompletesTheCheckWithItsReason() {
        publisher.publish(BuildFailed.of(orgId, "bld-1", appId.toString(), sha, UUID.randomUUID(), "No Dockerfile"));

        CheckRun row = awaitRevision(sha, 1);
        assertThat(row.desired().conclusion()).isEqualTo(Conclusion.FAILURE);
        assertThat(row.desired().summary()).isEqualTo("Build failed: No Dockerfile");
    }

    @Test
    void aLiveDeployLinksTheLiveUrlAndAFailedOneTurnsTheCheckToFailure() {
        publisher.publish(BuildSucceeded.of(orgId, "bld-1", appId.toString(), sha, UUID.randomUUID()));
        awaitRevision(sha, 1);

        publisher.publish(DeployStateChanged.of(
                orgId, "dep-1", "HEALTH_CHECKING", "LIVE", appId.toString(), sha, "https://web.acme.app"));

        CheckRun live = awaitRevision(sha, 2);
        assertThat(live.desired().conclusion()).isEqualTo(Conclusion.SUCCESS);
        assertThat(live.desired().detailsUrl()).isEqualTo("https://web.acme.app");

        publisher.publish(
                DeployStateChanged.of(orgId, "dep-2", "HEALTH_CHECKING", "FAILED", appId.toString(), sha, null));

        assertThat(awaitRevision(sha, 3).desired().conclusion()).isEqualTo(Conclusion.FAILURE);
    }

    @Test
    void aStartedArrivingAfterItsBuildSucceededDoesNotReopenTheCheck() {
        publisher.publish(BuildSucceeded.of(orgId, "bld-1", appId.toString(), sha, UUID.randomUUID()));
        awaitRevision(sha, 1);
        BuildStarted late = BuildStarted.of(orgId, "bld-1", appId.toString(), sha, UUID.randomUUID());

        publisher.publish(late);

        awaitClaimed(BuildEventListener.STARTED_CONSUMER, late.eventId());
        CheckRun row = checkRuns.find(orgId, appId, sha).orElseThrow();
        assertThat(row.desired().state()).isEqualTo(CheckState.COMPLETED);
        assertThat(row.desiredRevision()).isEqualTo(1);
    }

    @Test
    void aRedeliveredEventIsANoOp() {
        publisher.publish(BuildStarted.of(orgId, "bld-1", appId.toString(), sha, UUID.randomUUID()));
        awaitRevision(sha, 1);
        BuildSucceeded succeeded = BuildSucceeded.of(orgId, "bld-1", appId.toString(), sha, UUID.randomUUID());
        publisher.publish(succeeded);
        awaitRevision(sha, 2);
        jdbc.update(
                "UPDATE git_integration.check_runs SET reported_revision = desired_revision WHERE org_id = ?", orgId);
        double duplicatesBefore = duplicates(BuildEventListener.SUCCEEDED_CONSUMER);

        publisher.publish(succeeded);

        await().atMost(WAIT).until(() -> duplicates(BuildEventListener.SUCCEEDED_CONSUMER) > duplicatesBefore);
        CheckRun row = checkRuns.find(orgId, appId, sha).orElseThrow();
        assertThat(row.desiredRevision()).isEqualTo(2);
        assertThat(row.pending()).isFalse();
    }

    @Test
    void anEventForAnotherOrgsAppIsDroppedAndCounted() {
        String intruder = fixtures.newOrg();
        BuildStarted event = BuildStarted.of(intruder, "bld-1", appId.toString(), sha, UUID.randomUUID());
        double droppedBefore = dropped("unlinked");

        publisher.publish(event);

        awaitClaimed(BuildEventListener.STARTED_CONSUMER, event.eventId());
        assertThat(dropped("unlinked") - droppedBefore).isEqualTo(1);
        assertThat(rows(sha)).isZero();
    }

    @Test
    void anEventForAnUnlinkedAppIsDroppedAndCounted() {
        UUID unlinked = fixtures.newApp(orgId, "ACTIVE");
        BuildStarted event = BuildStarted.of(orgId, "bld-1", unlinked.toString(), sha, UUID.randomUUID());
        double droppedBefore = dropped("unlinked");

        publisher.publish(event);

        awaitClaimed(BuildEventListener.STARTED_CONSUMER, event.eventId());
        assertThat(dropped("unlinked") - droppedBefore).isEqualTo(1);
        assertThat(rows(sha)).isZero();
    }

    @Test
    void aDeployChangeNamingNoCommitIsCountedAndAcknowledged() {
        double untrackedBefore = dropped("untracked");

        publisher.publish(DeployStateChanged.of(orgId, "dep-1", "BUILDING", "ROUTING"));

        await().atMost(WAIT).until(() -> dropped("untracked") > untrackedBefore);
        assertThat(rows(sha)).isZero();
    }

    @Test
    void aMalformedCommitShaIsDeadLetteredWithNothingStored() {
        BuildStarted malformed =
                BuildStarted.of(orgId, "bld-1", appId.toString(), sha.toUpperCase(), UUID.randomUUID());
        double failuresBefore = failures(BuildEventListener.STARTED_CONSUMER);

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), Topics.deadLetter(Topics.BUILD_STARTED))) {
            publisher.publish(malformed);

            assertThat(probe.awaitKey(orgId, 1)).hasSize(1);
        }
        assertThat(failures(BuildEventListener.STARTED_CONSUMER) - failuresBefore)
                .as("dead-lettered without a retry")
                .isEqualTo(1.0);
        assertThat(claims(BuildEventListener.STARTED_CONSUMER, malformed.eventId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.check_runs WHERE org_id = ?", Integer.class, orgId))
                .isZero();
    }

    private CheckRun awaitRevision(String commitSha, int revision) {
        return await().atMost(WAIT)
                .until(
                        () -> checkRuns.find(orgId, appId, commitSha),
                        found -> found.filter(row -> row.desiredRevision() == revision)
                                .isPresent())
                .orElseThrow();
    }

    private void awaitClaimed(String consumer, UUID eventId) {
        await().atMost(WAIT).until(() -> claims(consumer, eventId) == 1);
    }

    private int claims(String consumer, UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.processed_events WHERE consumer = ? AND event_id = ?",
                Integer.class,
                consumer,
                eventId);
    }

    private int rows(String commitSha) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.check_runs WHERE commit_sha = ?", Integer.class, commitSha);
    }

    private double duplicates(String consumer) {
        return count(meters.find("git." + TransactionalInbox.DUPLICATES)
                .tag(TransactionalInbox.TAG_LISTENER, consumer)
                .counter());
    }

    private double failures(String consumer) {
        return count(meters.find(MetricsCatalog.EVENTS_FAILED)
                .tag(MetricsCatalog.TAG_LISTENER, consumer)
                .counter());
    }

    private double dropped(String reason) {
        return count(
                meters.find(MetricsCatalog.CHECKS_DROPPED).tag("reason", reason).counter());
    }

    private static double count(Counter counter) {
        return counter == null ? 0 : counter.count();
    }

    private static String newSha() {
        byte[] bytes = new byte[20];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
