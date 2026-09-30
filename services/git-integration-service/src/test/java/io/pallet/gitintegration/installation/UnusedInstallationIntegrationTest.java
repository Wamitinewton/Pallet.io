package io.pallet.gitintegration.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.installation.LifecycleScenario.Notice;
import io.pallet.gitintegration.installation.UnusedInstallationSweep.Outcome;
import io.pallet.gitintegration.installation.UnusedInstallationSweep.Run;
import io.pallet.gitintegration.observability.MetricsCatalog;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.Tokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class, Tokens.LocalDecoder.class})
class UnusedInstallationIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final int GRACE_DAYS = 30;

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private UnusedInstallationSweep sweep;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private MeterRegistry meters;

    private LifecycleScenario scenario;
    private InstallationApi api;
    private long installationId;

    @BeforeEach
    void setUp() {
        scenario = new LifecycleScenario(jdbc, processor);
        api = new InstallationApi(mvc, json, github);
        installationId = scenario.fixtures.newInstallation();
    }

    @AfterEach
    void tearDown() {
        scenario.cleanUp();
    }

    @Test
    void theLastUnlinkStartsTheClockAndRemindsThatOrgsAdminsOnceWithTheUninstallDate() throws Exception {
        String first = scenario.orgLinkedTo(installationId);
        String last = scenario.orgLinkedTo(installationId);

        api.as(scenario.fixtures.newMember(first, "admin", "ACTIVE"));
        assertThat(api.unlink(first, installationId).getStatus()).isEqualTo(204);

        assertThat(unusedSince(installationId)).isNull();
        assertThat(reminders(first)).isEmpty();

        api.as(scenario.fixtures.newMember(last, "admin", "ACTIVE"));
        assertThat(api.unlink(last, installationId).getStatus()).isEqualTo(204);

        Instant unusedSince = unusedSince(installationId);
        assertThat(unusedSince).isNotNull();
        assertThat(reminders(first)).isEmpty();
        assertThat(reminders(last)).singleElement().satisfies(notice -> {
            assertThat(notice.audience()).isEqualTo(NotificationRequested.AUDIENCE_ORG_ADMINS);
            assertThat(notice.dedupeKey()).isEqualTo("git-installation-unused:" + installationId + ":" + unusedSince);
            assertThat(notice.variables().path("accountLogin").asString()).isEqualTo("acme");
            assertThat(notice.variables().path("settingsUrl").asString())
                    .isEqualTo(
                            GitHubApiStub.baseUrl() + "/organizations/acme/settings/installations/" + installationId);
            assertThat(notice.variables().path("uninstallAfter").asString())
                    .isEqualTo(LocalDate.ofInstant(unusedSince.plus(Duration.ofDays(GRACE_DAYS)), ZoneOffset.UTC)
                            .toString());
        });
    }

    @Test
    void aDeletedOrgStartsTheClockWithoutAReminder() {
        String orgId = scenario.orgLinkedTo(installationId);

        publisher.publish(OrgDeleted.of(orgId, "user-owner"));

        await().atMost(WAIT).until(() -> "UNLINKED".equals(scenario.linkStatus(installationId, orgId)));
        assertThat(unusedSince(installationId)).isNotNull();
        assertThat(scenario.events(orgId, NotificationRequested.TYPE)).isZero();
    }

    @Test
    void relinkingClearsTheClockAndTheSweepLeavesTheInstallationAlone() throws Exception {
        String orgId = scenario.orgLinkedTo(installationId);
        api.as(scenario.fixtures.newMember(orgId, "admin", "ACTIVE"));
        assertThat(api.unlink(orgId, installationId).getStatus()).isEqualTo(204);
        assertThat(unusedSince(installationId)).isNotNull();

        api.signIn();
        github.stubUserInstallationPages(List.of(List.of(installationId)));
        github.stubInstallation(installationId);
        assertThat(api.linkExisting(orgId, installationId).getStatus()).isEqualTo(201);

        assertThat(unusedSince(installationId)).isNull();
        github.stubUninstall(installationId, 204);
        sweep.run().orElseThrow();
        assertThat(github.uninstallRequests(installationId)).isEmpty();
    }

    @Test
    void theSweepWaitsOutTheWholeGracePeriodThenUninstallsOnce() {
        String orgId = unusedAfterUnlink(installationId);
        github.stubUninstall(installationId, 204);
        double uninstalledBefore = uninstalled();

        age(installationId, Duration.ofDays(GRACE_DAYS - 1));
        sweep.run().orElseThrow();
        assertThat(github.uninstallRequests(installationId)).isEmpty();

        age(installationId, Duration.ofDays(GRACE_DAYS));
        Run run = sweep.run().orElseThrow();

        assertThat(run.outcomes()).containsEntry(Outcome.UNINSTALLED, 1);
        assertThat(github.uninstallRequests(installationId)).hasSize(1);
        assertThat(uninstalled() - uninstalledBefore).isEqualTo(1);
        assertThat(scenario.installation(installationId)).containsEntry("status", "ACTIVE");
        assertThat(scenario.audits(orgId, AuditEvents.INSTALLATION_UNINSTALLED))
                .singleElement()
                .satisfies(audit -> {
                    assertThat(audit.path("actor").asString()).isEqualTo("system");
                    assertThat(audit.path("context").path("reason").asString())
                            .isEqualTo(UnusedInstallationSweep.REASON_UNUSED);
                    assertThat(audit.path("context").path("daysUnused").asLong())
                            .isEqualTo(GRACE_DAYS);
                });

        assertThat(scenario.deliver("installation-deleted.json", Map.of("/installation/id", installationId)))
                .containsEntry("status", "PROCESSED");
        assertThat(scenario.installation(installationId)).containsEntry("status", "DELETED");
        assertThat(scenario.events(orgId, NotificationRequested.TYPE)).isOne();
    }

    @Test
    void anInstallationRelinkedAfterSelectionIsSkipped() {
        long older = scenario.fixtures.newInstallation();
        unusedAfterUnlink(older);
        unusedAfterUnlink(installationId);
        age(older, Duration.ofDays(GRACE_DAYS + 2));
        age(installationId, Duration.ofDays(GRACE_DAYS + 1));
        github.stubUninstall(older, 204);
        github.stubUninstall(installationId, 204);
        String relinker = scenario.fixtures.newOrg();
        github.beforeAnswering(GitHubApiStub.installationPath(older), () -> {
            jdbc.update(
                    "UPDATE git_integration.installations SET unused_since = NULL WHERE installation_id = ?",
                    installationId);
            scenario.fixtures.linkInstallation(installationId, relinker, "ACTIVE");
        });

        Run run = sweep.run().orElseThrow();

        assertThat(github.uninstallRequests(older)).hasSize(1);
        assertThat(github.uninstallRequests(installationId)).isEmpty();
        assertThat(run.outcomes()).containsEntry(Outcome.STILL_IN_USE, 1);
        assertThat(scenario.linkStatus(installationId, relinker)).isEqualTo("ACTIVE");
    }

    @Test
    void anInstallationGitHubNoLongerHasIsMarkedDeletedSoItIsNotAskedAgain() {
        unusedAfterUnlink(installationId);
        age(installationId, Duration.ofDays(GRACE_DAYS));
        github.stubUninstall(installationId, 404);

        Run run = sweep.run().orElseThrow();

        assertThat(run.outcomes()).containsEntry(Outcome.ALREADY_GONE, 1);
        assertThat(scenario.installation(installationId))
                .containsEntry("status", "DELETED")
                .containsEntry("unused_since", null);

        sweep.run().orElseThrow();
        assertThat(github.uninstallRequests(installationId)).hasSize(1);
    }

    @Test
    void gitHubFailingStopsTheRunAndLeavesEveryInstallationUntouched() {
        long later = scenario.fixtures.newInstallation();
        String orgId = unusedAfterUnlink(installationId);
        unusedAfterUnlink(later);
        age(installationId, Duration.ofDays(GRACE_DAYS + 2));
        age(later, Duration.ofDays(GRACE_DAYS + 1));
        Instant unusedSince = unusedSince(installationId);
        github.stubUninstall(installationId, 503);
        github.stubUninstall(later, 204);

        Run run = sweep.run().orElseThrow();

        assertThat(run.stoppedEarly()).isTrue();
        assertThat(github.uninstallRequests(installationId)).isNotEmpty();
        assertThat(github.uninstallRequests(later)).isEmpty();
        assertThat(scenario.installation(installationId)).containsEntry("status", "ACTIVE");
        assertThat(unusedSince(installationId)).isEqualTo(unusedSince);
        assertThat(scenario.audits(orgId, AuditEvents.INSTALLATION_UNINSTALLED)).isEmpty();
    }

    @Test
    void aNeverLinkedInstallationIsUninstalledOnTheSameClockFromItsCreation() {
        long created = scenario.fixtures.newInstallationId();
        assertThat(scenario.deliver("installation-created.json", Map.of("/installation/id", created)))
                .containsEntry("status", "PROCESSED");
        assertThat(unusedSince(created)).isNotNull();
        age(created, Duration.ofDays(GRACE_DAYS));
        github.stubUninstall(created, 204);

        Run run = sweep.run().orElseThrow();

        assertThat(run.outcomes()).containsEntry(Outcome.UNINSTALLED, 1);
        assertThat(github.uninstallRequests(created)).hasSize(1);
    }

    private String unusedAfterUnlink(long installation) {
        String orgId = scenario.orgLinkedTo(installation);
        try {
            api.as(scenario.fixtures.newMember(orgId, "admin", "ACTIVE"));
            assertThat(api.unlink(orgId, installation).getStatus()).isEqualTo(204);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(unusedSince(installation)).isNotNull();
        return orgId;
    }

    private void age(long installation, Duration unusedFor) {
        jdbc.update("""
                UPDATE git_integration.installations SET unused_since = now() - make_interval(secs => ?)
                 WHERE installation_id = ?
                """, (double) unusedFor.toSeconds(), installation);
    }

    private Instant unusedSince(long installation) {
        Timestamp unusedSince = jdbc.queryForObject(
                "SELECT unused_since FROM git_integration.installations WHERE installation_id = ?",
                Timestamp.class,
                installation);
        return unusedSince == null ? null : unusedSince.toInstant();
    }

    private List<Notice> reminders(String orgId) {
        return scenario.notices(orgId).stream()
                .filter(notice -> UnusedInstallationNotifier.NOTIFICATION_TYPE.equals(notice.type()))
                .toList();
    }

    private double uninstalled() {
        return meters.counter(MetricsCatalog.INSTALLATIONS_UNINSTALLED).count();
    }
}
