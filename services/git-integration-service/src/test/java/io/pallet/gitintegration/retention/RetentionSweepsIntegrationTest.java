package io.pallet.gitintegration.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.observability.MetricsCatalog;
import io.pallet.gitintegration.retention.RetentionSweeps.Run;
import io.pallet.gitintegration.retention.RetentionSweeps.Sweep;
import io.pallet.gitintegration.support.ReadModelFixtures;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class RetentionSweepsIntegrationTest {

    private static final Duration MARGIN = Duration.ofHours(1);
    private static final Duration PAYLOAD_WINDOW = Duration.ofDays(7);
    private static final Duration DELIVERY_WINDOW = Duration.ofDays(30);
    private static final Duration OUTBOX_WINDOW = Duration.ofDays(7);
    private static final Duration INBOX_WINDOW = Duration.ofDays(14);
    private static final Duration STATE_WINDOW = Duration.ofDays(1);
    private static final Duration MANUAL_BUILD_WINDOW = Duration.ofDays(7);
    private static final Duration CHECK_RUN_WINDOW = Duration.ofDays(30);
    private static final Duration TERMINAL_WINDOW = Duration.ofDays(90);

    @Autowired
    private RetentionSweeps sweeps;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private ReadModelFixtures fixtures;
    private RetentionRows rows;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        rows = new RetentionRows(jdbc);
    }

    @AfterEach
    void tearDown() {
        rows.removeAll();
        fixtures.cleanUp();
    }

    @Test
    void payloadsOfFinishedDeliveriesAreNulledOnceSevenDaysOld() {
        UUID outside = rows.delivery("PROCESSED", outside(PAYLOAD_WINDOW));
        UUID inside = rows.delivery("PROCESSED", inside(PAYLOAD_WINDOW));
        UUID ignored = rows.delivery("IGNORED", outside(PAYLOAD_WINDOW));
        UUID parked = rows.delivery("PARKED", outside(PAYLOAD_WINDOW));
        UUID pending = rows.delivery("RECEIVED", outside(PAYLOAD_WINDOW));

        sweep(Sweep.DELIVERY_PAYLOADS);

        assertThat(rows.payloadNulled(outside)).isTrue();
        assertThat(rows.payloadNulled(ignored)).isTrue();
        assertThat(rows.payloadNulled(parked)).isTrue();
        assertThat(rows.payloadNulled(inside)).isFalse();
        assertThat(rows.payloadNulled(pending)).isFalse();
        assertThat(List.of(outside, inside, ignored, parked, pending)).allMatch(rows::deliveryExists);
    }

    @Test
    void finishedDeliveriesAreDeletedAtThirtyDaysButPendingAndParkedOnesNeverAre() {
        UUID outside = rows.delivery("PROCESSED", outside(DELIVERY_WINDOW));
        UUID ignored = rows.delivery("IGNORED", outside(DELIVERY_WINDOW));
        UUID inside = rows.delivery("PROCESSED", inside(DELIVERY_WINDOW));
        UUID pending = rows.delivery("RECEIVED", Duration.ofDays(365));
        UUID parked = rows.delivery("PARKED", Duration.ofDays(365));

        sweep(Sweep.DELIVERIES);

        assertThat(rows.deliveryExists(outside)).isFalse();
        assertThat(rows.deliveryExists(ignored)).isFalse();
        assertThat(rows.deliveryExists(inside)).isTrue();
        assertThat(rows.deliveryExists(pending)).isTrue();
        assertThat(rows.deliveryExists(parked)).isTrue();
    }

    @Test
    void theOutboxSweepDeletesOnlyPublishedRowsPastTheOutboxWindow() {
        String orgId = fixtures.newOrg();
        UUID outside = rows.outboxEvent(orgId, outside(OUTBOX_WINDOW), outside(OUTBOX_WINDOW));
        UUID inside = rows.outboxEvent(orgId, inside(OUTBOX_WINDOW), outside(OUTBOX_WINDOW));
        UUID unpublished = rows.outboxEvent(orgId, null, Duration.ofDays(365));

        sweep(Sweep.OUTBOX);

        assertThat(rows.outboxEventExists(outside)).isFalse();
        assertThat(rows.outboxEventExists(inside)).isTrue();
        assertThat(rows.outboxEventExists(unpublished)).isTrue();
    }

    @Test
    void theInboxSweepDeletesClaimsPastTheInboxWindow() {
        UUID outside = rows.processedEvent(outside(INBOX_WINDOW));
        UUID inside = rows.processedEvent(inside(INBOX_WINDOW));

        sweep(Sweep.INBOX);

        assertThat(rows.processedEventExists(outside)).isFalse();
        assertThat(rows.processedEventExists(inside)).isTrue();
    }

    @Test
    void authorizationStatesGoOneDayAfterTheyExpire() {
        UUID outside = rows.authorizationState(outside(STATE_WINDOW));
        UUID inside = rows.authorizationState(inside(STATE_WINDOW));

        sweep(Sweep.AUTHORIZATION_STATES);

        assertThat(rows.authorizationStateExists(outside)).isFalse();
        assertThat(rows.authorizationStateExists(inside)).isTrue();
    }

    @Test
    void manualBuildRequestsGoAfterSevenDays() {
        String orgId = fixtures.newOrg();
        UUID appId = UUID.randomUUID();
        String outside = rows.manualBuildRequest(orgId, appId, outside(MANUAL_BUILD_WINDOW));
        String inside = rows.manualBuildRequest(orgId, appId, inside(MANUAL_BUILD_WINDOW));

        sweep(Sweep.MANUAL_BUILD_REQUESTS);

        assertThat(rows.manualBuildRequestExists(outside)).isFalse();
        assertThat(rows.manualBuildRequestExists(inside)).isTrue();
    }

    @Test
    void onlyCompletedCheckRunsWithNothingLeftToReportGoAfterThirtyDays() {
        String orgId = fixtures.newOrg();
        UUID outside = rows.checkRun(orgId, "completed", true, outside(CHECK_RUN_WINDOW));
        UUID inside = rows.checkRun(orgId, "completed", true, inside(CHECK_RUN_WINDOW));
        UUID unreported = rows.checkRun(orgId, "completed", false, Duration.ofDays(365));
        UUID running = rows.checkRun(orgId, "in_progress", true, Duration.ofDays(365));

        sweep(Sweep.CHECK_RUNS);

        assertThat(rows.checkRunExists(outside)).isFalse();
        assertThat(rows.checkRunExists(inside)).isTrue();
        assertThat(rows.checkRunExists(unreported)).isTrue();
        assertThat(rows.checkRunExists(running)).isTrue();
    }

    @Test
    void disconnectedRepoLinksGoAfterNinetyDaysWithTheirBranchHeads() {
        String orgId = fixtures.newOrg();
        long installationId = fixtures.newInstallation();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        UUID outside = fixtures.newRepoLink(orgId, installationId, 1001);
        UUID inside = fixtures.newRepoLink(orgId, installationId, 1002);
        UUID active = fixtures.newRepoLink(orgId, installationId, 1003);
        rows.disconnectRepoLink(outside, outside(TERMINAL_WINDOW));
        rows.disconnectRepoLink(inside, inside(TERMINAL_WINDOW));
        rows.branchHead(outside);
        rows.branchHead(inside);

        sweep(Sweep.REPO_LINKS);

        assertThat(rows.repoLinkExists(outside)).isFalse();
        assertThat(rows.branchHeads(outside)).isZero();
        assertThat(rows.repoLinkExists(inside)).isTrue();
        assertThat(rows.branchHeads(inside)).isOne();
        assertThat(rows.repoLinkExists(active)).isTrue();
    }

    @Test
    void anUnlinkedInstallationLinkStaysWhileADisconnectedRepoLinkStillReferencesIt() {
        long installationId = fixtures.newInstallation();
        String referenced = fixtures.newOrg();
        fixtures.linkInstallation(installationId, referenced, "ACTIVE");
        UUID repoLink = fixtures.newRepoLink(referenced, installationId, 2001);
        rows.disconnectRepoLink(repoLink, inside(TERMINAL_WINDOW));
        rows.unlinkInstallation(installationId, referenced, outside(TERMINAL_WINDOW));
        String unreferenced = fixtures.newOrg();
        fixtures.linkInstallation(installationId, unreferenced, "UNLINKED");
        rows.unlinkInstallation(installationId, unreferenced, outside(TERMINAL_WINDOW));
        String young = fixtures.newOrg();
        fixtures.linkInstallation(installationId, young, "UNLINKED");
        rows.unlinkInstallation(installationId, young, inside(TERMINAL_WINDOW));

        sweepDaily();

        assertThat(rows.installationLinkExists(installationId, unreferenced)).isFalse();
        assertThat(rows.installationLinkExists(installationId, referenced)).isTrue();
        assertThat(rows.installationLinkExists(installationId, young)).isTrue();

        rows.disconnectRepoLink(repoLink, outside(TERMINAL_WINDOW));
        sweepDaily();

        assertThat(rows.repoLinkExists(repoLink)).isFalse();
        assertThat(rows.installationLinkExists(installationId, referenced)).isFalse();
        assertThat(rows.installationLinkExists(installationId, young)).isTrue();
    }

    @Test
    void deletedInstallationsGoWithTheirRepositoriesOnceNoLinkReferencesThem() {
        long gone = fixtures.newInstallation();
        rows.installationRepository(gone, 3001);
        rows.deleteInstallation(gone, outside(TERMINAL_WINDOW));
        long linked = fixtures.newInstallation();
        String orgId = fixtures.newOrg();
        fixtures.linkInstallation(linked, orgId, "UNLINKED");
        rows.unlinkInstallation(linked, orgId, inside(TERMINAL_WINDOW));
        rows.installationRepository(linked, 3002);
        rows.deleteInstallation(linked, outside(TERMINAL_WINDOW));
        long young = fixtures.newInstallation();
        rows.deleteInstallation(young, inside(TERMINAL_WINDOW));
        long active = fixtures.newInstallation();

        sweep(Sweep.INSTALLATIONS);

        assertThat(rows.installationExists(gone)).isFalse();
        assertThat(rows.installationRepositories(gone)).isZero();
        assertThat(rows.installationExists(linked)).isTrue();
        assertThat(rows.installationRepositories(linked)).isOne();
        assertThat(rows.installationExists(young)).isTrue();
        assertThat(rows.installationExists(active)).isTrue();

        rows.unlinkInstallation(linked, orgId, outside(TERMINAL_WINDOW));
        sweepDaily();

        assertThat(rows.installationLinkExists(linked, orgId)).isFalse();
        assertThat(rows.installationExists(linked)).isFalse();
        assertThat(rows.installationRepositories(linked)).isZero();
    }

    @Test
    void deletedAppsGoAfterNinetyDaysOnceNoRepoLinkReferencesThem() {
        String orgId = fixtures.newOrg();
        long installationId = fixtures.newInstallation();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        UUID unreferenced = fixtures.newApp(orgId, "ACTIVE");
        rows.deleteApp(unreferenced, outside(TERMINAL_WINDOW));
        UUID referenced = fixtures.newRepoLink(orgId, installationId, 4001);
        rows.disconnectRepoLink(referenced, inside(TERMINAL_WINDOW));
        rows.deleteApp(referenced, outside(TERMINAL_WINDOW));
        UUID young = fixtures.newApp(orgId, "ACTIVE");
        rows.deleteApp(young, inside(TERMINAL_WINDOW));
        UUID active = fixtures.newApp(orgId, "ACTIVE");

        sweep(Sweep.APPS);

        assertThat(rows.appExists(unreferenced)).isFalse();
        assertThat(rows.appExists(referenced)).isTrue();
        assertThat(rows.appExists(young)).isTrue();
        assertThat(rows.appExists(active)).isTrue();
    }

    @Test
    void deletedOrgsGoAfterNinetyDaysUnlessAMembershipIsStillActive() {
        String outside = fixtures.newOrg();
        rows.deletedOrg(outside, outside(TERMINAL_WINDOW));
        String inside = fixtures.newOrg();
        rows.deletedOrg(inside, inside(TERMINAL_WINDOW));
        String stillActive = fixtures.newOrg();
        rows.deletedOrg(stillActive, outside(TERMINAL_WINDOW));
        fixtures.newMember(stillActive, "admin", "ACTIVE");
        String removedMembers = fixtures.newOrg();
        rows.deletedOrg(removedMembers, outside(TERMINAL_WINDOW));
        fixtures.newMember(removedMembers, "admin", "REMOVED");

        sweep(Sweep.DELETED_ORGS);

        assertThat(rows.deletedOrgExists(outside)).isFalse();
        assertThat(rows.deletedOrgExists(removedMembers)).isFalse();
        assertThat(rows.deletedOrgExists(inside)).isTrue();
        assertThat(rows.deletedOrgExists(stillActive)).isTrue();
    }

    @Test
    void aSweepWorksThroughItsRowsInBatchesUntilOneComesBackShort() {
        sweep(Sweep.DELIVERIES);
        List<UUID> old = rows.deliveries("PROCESSED", outside(DELIVERY_WINDOW), 3_500);
        double deletedBefore = deleted("webhook_deliveries");

        Run run = sweep(Sweep.DELIVERIES);

        assertThat(run.rows()).isEqualTo(3_500);
        assertThat(run.batches()).isEqualTo(4);
        assertThat(run.complete()).isTrue();
        assertThat(old).noneMatch(rows::deliveryExists);
        assertThat(deleted("webhook_deliveries") - deletedBefore).isEqualTo(3_500);
        assertThat(meters.find(MetricsCatalog.RETENTION_RUN_DURATION)
                        .tag("sweep", "deliveries")
                        .timer()
                        .count())
                .isPositive();
    }

    private Run sweep(Sweep sweep) {
        return sweeps.run(sweep).orElseThrow();
    }

    private void sweepDaily() {
        RetentionSweeps.DAILY.forEach(this::sweep);
    }

    private double deleted(String table) {
        return meters.find(MetricsCatalog.RETENTION_DELETED)
                .tag("table", table)
                .counter()
                .count();
    }

    private static Duration outside(Duration window) {
        return window.plus(MARGIN);
    }

    private static Duration inside(Duration window) {
        return window.minus(MARGIN);
    }
}
