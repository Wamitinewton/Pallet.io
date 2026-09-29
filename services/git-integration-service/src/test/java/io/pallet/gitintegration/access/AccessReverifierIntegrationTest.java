package io.pallet.gitintegration.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.access.AccessReverifier.Run;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.installation.ConnectionLostNotifier;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import(RedisTestContainerConfiguration.class)
class AccessReverifierIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_CYCLES = 20;
    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);
    private static final Duration DUE = Duration.ofDays(2);
    private static final long VERIFIER_ID = 7001;
    private static final String VERIFIER = "octo-verifier";

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        GitHubApiStub.register(registry);
    }

    @Autowired
    private AccessReverifier reverifier;

    @Autowired
    private ReverifyMetrics metrics;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DeliveryProcessor processor;

    private ReadModelFixtures fixtures;
    private final List<UUID> deliveries = new ArrayList<>();
    private long installationId;
    private long repoId;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        installationId = fixtures.newInstallation();
        repoId = newRepoId();
        github.stubTokenMint(installationId);
    }

    @AfterEach
    void tearDown() {
        deliveries.forEach(
                id -> jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id));
        fixtures.cleanUp();
    }

    @Test
    void aVerifierAtOrAboveTheFloorIsConfirmedWithTheirCurrentRole() {
        String orgId = orgOn(installationId);
        UUID appId = link(orgId, installationId, repoId, VERIFIER_ID, VERIFIER, DUE);
        long version = link(appId).version();
        github.stubCollaboratorPermission(repoId, VERIFIER, VERIFIER_ID, "maintain", "write");
        double confirmedBefore = checked("confirmed");

        Run run = reverifier.run().orElseThrow();

        assertThat(run.checked()).containsOnly(Map.entry(ReverifyOutcome.Kind.CONFIRMED, 1));
        assertThat(checked("confirmed") - confirmedBefore).isEqualTo(1);
        Row link = link(appId);
        assertThat(link.status()).isEqualTo("ACTIVE");
        assertThat(link.permission()).isEqualTo("maintain");
        assertThat(link.version()).isEqualTo(version);
        assertThat(link.checkedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(notices(orgId)).isEmpty();
    }

    @Test
    void aCustomRoleCountsAsTheBaseRoleGitHubReportsUnderIt() {
        UUID appId = link(orgOn(installationId), installationId, repoId, VERIFIER_ID, VERIFIER, DUE);
        github.stubCollaboratorPermission(repoId, VERIFIER, VERIFIER_ID, "release-manager", "write");

        reverifier.run().orElseThrow();

        assertThat(link(appId).status()).isEqualTo("ACTIVE");
        assertThat(link(appId).permission()).isEqualTo("push");
    }

    @Test
    void aVerifierBelowTheFloorLosesOnlyTheirOrgsLinkWhichStopsBuilding() {
        String lostOrg = orgOn(installationId);
        String keptOrg = orgOn(installationId);
        UUID lostApp = link(lostOrg, installationId, repoId, VERIFIER_ID, VERIFIER, DUE);
        UUID keptApp = link(keptOrg, installationId, repoId, 7002, "octo-keeper", DUE);
        head(lostApp, A);
        head(keptApp, A);
        github.stubCollaboratorPermission(repoId, VERIFIER, VERIFIER_ID, "read", "read");
        github.stubCollaboratorPermission(repoId, "octo-keeper", 7002, "write", "write");
        double disconnectedBefore = meters.counter(ReverifyMetrics.DISCONNECTED).count();

        Run run = reverifier.run().orElseThrow();

        assertThat(run.disconnected()).isEqualTo(1);
        assertThat(meters.counter(ReverifyMetrics.DISCONNECTED).count() - disconnectedBefore)
                .isEqualTo(1);
        Row lost = link(lostApp);
        assertThat(lost.status()).isEqualTo("DISCONNECTED");
        assertThat(lost.reason()).isEqualTo("VERIFIER_ACCESS_LOST");
        assertThat(head(lostApp)).isEmpty();
        assertThat(audits(lostOrg, AuditEvents.REPO_LINK_VERIFIER_ACCESS_LOST))
                .singleElement()
                .satisfies(audit -> {
                    JsonNode context = audit.path("context");
                    assertThat(context.path("verifierLogin").asString()).isEqualTo(VERIFIER);
                    assertThat(context.path("previousRole").asString()).isEqualTo("push");
                    assertThat(context.path("currentRole").asString()).isEqualTo("read");
                });
        assertThat(audits(lostOrg, AuditEvents.REPO_LINK_DISCONNECTED)).hasSize(1);
        assertThat(notices(lostOrg)).singleElement().satisfies(notice -> {
            assertThat(notice.path("notificationType").asString()).isEqualTo(ConnectionLostNotifier.NOTIFICATION_TYPE);
            assertThat(notice.path("audience").asString()).isEqualTo(NotificationRequested.AUDIENCE_ORG_ADMINS);
            assertThat(notice.path("variables").path("reason").asString())
                    .isEqualTo(ConnectionLostNotifier.Reason.VERIFIER_ACCESS_LOST.name());
            assertThat(notice.path("variables").path("repository").asString()).isEqualTo("octo-org/api");
        });
        assertThat(link(keptApp).status()).isEqualTo("ACTIVE");
        assertThat(notices(keptOrg)).isEmpty();

        postAndProcess(WebhookFixtures.delivery("push-main.json")
                .with("/installation/id", installationId)
                .with("/repository/id", repoId)
                .with("/before", A)
                .with("/after", B)
                .with("/head_commit/id", B));

        assertThat(pushes(lostOrg)).isZero();
        assertThat(pushes(keptOrg)).isEqualTo(1);
    }

    @Test
    void aVerifierWhoseAccountIsGoneLosesTheLink() {
        String orgId = orgOn(installationId);
        UUID appId = link(orgId, installationId, repoId, VERIFIER_ID, VERIFIER, DUE);
        github.stubNotFound(GitHubApiStub.collaboratorPermissionPath(repoId, VERIFIER));
        github.stubNotFound(GitHubApiStub.userByIdPath(VERIFIER_ID));

        reverifier.run().orElseThrow();

        assertThat(link(appId).status()).isEqualTo("DISCONNECTED");
        assertThat(audits(orgId, AuditEvents.REPO_LINK_VERIFIER_ACCESS_LOST))
                .singleElement()
                .satisfies(audit -> assertThat(
                                audit.path("context").path("currentRole").asString())
                        .isEqualTo("none"));
        assertThat(notices(orgId)).hasSize(1);
    }

    @Test
    void aRenamedVerifierIsFollowedByIdAndTheNewLoginIsStored() {
        UUID appId = link(orgOn(installationId), installationId, repoId, VERIFIER_ID, VERIFIER, DUE);
        github.stubNotFound(GitHubApiStub.collaboratorPermissionPath(repoId, VERIFIER));
        github.stubUserById(VERIFIER_ID, "octo-renamed");
        github.stubCollaboratorPermission(repoId, "octo-renamed", VERIFIER_ID, "write", "write");

        Run run = reverifier.run().orElseThrow();

        assertThat(run.checked()).containsOnly(Map.entry(ReverifyOutcome.Kind.CONFIRMED, 1));
        assertThat(link(appId).status()).isEqualTo("ACTIVE");
        assertThat(link(appId).login()).isEqualTo("octo-renamed");
    }

    @Test
    void aLoginThatNowAnswersForAnotherAccountIsNotTakenForTheVerifier() {
        UUID appId = link(orgOn(installationId), installationId, repoId, VERIFIER_ID, VERIFIER, DUE);
        github.stubCollaboratorPermission(repoId, VERIFIER, 9999, "admin", "admin");
        github.stubUserById(VERIFIER_ID, "octo-moved");
        github.stubCollaboratorPermission(repoId, "octo-moved", VERIFIER_ID, "read", "read");

        reverifier.run().orElseThrow();

        assertThat(link(appId).status()).isEqualTo("DISCONNECTED");
    }

    @Test
    void aRepositoryGitHubCannotFindIsLeftForTheRepositoryLifecycle() {
        String orgId = orgOn(installationId);
        UUID appId = link(orgId, installationId, repoId, VERIFIER_ID, VERIFIER, DUE);
        Instant checkedAt = link(appId).checkedAt();
        github.stubNotFound(GitHubApiStub.collaboratorPermissionPath(repoId, VERIFIER));
        github.stubUserById(VERIFIER_ID, VERIFIER);

        Run run = reverifier.run().orElseThrow();

        assertThat(run.checked()).containsOnly(Map.entry(ReverifyOutcome.Kind.UNKNOWN, 1));
        assertThat(link(appId).status()).isEqualTo("ACTIVE");
        assertThat(link(appId).checkedAt()).isEqualTo(checkedAt);
        assertThat(notices(orgId)).isEmpty();
    }

    @Test
    void aRateLimitLeavesTheInstallationsLinksUncheckedForTheNextRun() {
        String orgId = orgOn(installationId);
        UUID first = link(orgId, installationId, repoId, VERIFIER_ID, VERIFIER, DUE.plusHours(1));
        long otherRepo = newRepoId();
        UUID second = link(orgId, installationId, otherRepo, VERIFIER_ID, VERIFIER, DUE);
        Instant firstCheckedAt = link(first).checkedAt();
        Instant secondCheckedAt = link(second).checkedAt();
        github.stubRateLimited(
                GitHubApiStub.collaboratorPermissionPath(repoId, VERIFIER),
                Instant.now().plus(1, ChronoUnit.HOURS));
        github.stubCollaboratorPermission(otherRepo, VERIFIER, VERIFIER_ID, "read", "read");

        Run run = reverifier.run().orElseThrow();

        assertThat(run.checked()).containsOnly(Map.entry(ReverifyOutcome.Kind.UNKNOWN, 1));
        assertThat(github.requests(GitHubApiStub.collaboratorPermissionPath(otherRepo, VERIFIER)))
                .isEmpty();
        assertThat(link(first)).satisfies(row -> {
            assertThat(row.status()).isEqualTo("ACTIVE");
            assertThat(row.checkedAt()).isEqualTo(firstCheckedAt);
        });
        assertThat(link(second)).satisfies(row -> {
            assertThat(row.status()).isEqualTo("ACTIVE");
            assertThat(row.checkedAt()).isEqualTo(secondCheckedAt);
        });
    }

    @Test
    void gitHubFailingLeavesEveryLinkActiveAndUnchecked() {
        long otherInstallation = fixtures.newInstallation();
        long otherRepo = newRepoId();
        github.stubTokenMint(otherInstallation);
        List<UUID> apps = List.of(
                link(orgOn(installationId), installationId, repoId, VERIFIER_ID, VERIFIER, DUE),
                link(orgOn(otherInstallation), otherInstallation, otherRepo, VERIFIER_ID, VERIFIER, DUE));
        List<Instant> checkedAt =
                apps.stream().map(app -> link(app).checkedAt()).toList();
        github.stub5xx(GitHubApiStub.collaboratorPermissionPath(repoId, VERIFIER));
        github.stub5xx(GitHubApiStub.collaboratorPermissionPath(otherRepo, VERIFIER));

        Run run = reverifier.run().orElseThrow();

        assertThat(run.disconnected()).isZero();
        assertThat(run.checked()).containsOnly(Map.entry(ReverifyOutcome.Kind.UNKNOWN, 1));
        assertThat(apps).extracting(app -> link(app).status()).containsOnly("ACTIVE");
        assertThat(apps).extracting(app -> link(app).checkedAt()).isEqualTo(checkedAt);
    }

    @Test
    void aVerifierHandoverCommittedDuringTheCheckWinsOverTheStaleLoss() {
        String orgId = orgOn(installationId);
        UUID appId = link(orgId, installationId, repoId, VERIFIER_ID, VERIFIER, DUE);
        github.stubCollaboratorPermission(repoId, VERIFIER, VERIFIER_ID, "read", "read");
        github.beforeAnswering(
                GitHubApiStub.collaboratorPermissionPath(repoId, VERIFIER), () -> jdbc.update("""
                        UPDATE git_integration.repo_links
                           SET verified_github_user_id = 7002, verified_github_login = 'octo-successor',
                               verified_permission = 'admin', access_checked_at = now(), version = version + 1
                         WHERE app_id = ?
                        """, appId));

        Run run = reverifier.run().orElseThrow();

        assertThat(run.stale()).isEqualTo(1);
        assertThat(run.disconnected()).isZero();
        assertThat(link(appId)).satisfies(row -> {
            assertThat(row.status()).isEqualTo("ACTIVE");
            assertThat(row.login()).isEqualTo("octo-successor");
        });
        assertThat(notices(orgId)).isEmpty();
    }

    @Test
    void twoRunsStartedTogetherLeaveTheWorkToOne() throws Exception {
        UUID appId = link(orgOn(installationId), installationId, repoId, VERIFIER_ID, VERIFIER, DUE);
        github.stubCollaboratorPermission(repoId, VERIFIER, VERIFIER_ID, "write", "write");
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        github.beforeAnswering(GitHubApiStub.collaboratorPermissionPath(repoId, VERIFIER), () -> {
            checking.countDown();
            await(secondFinished);
        });

        CompletableFuture<Optional<Run>> first = CompletableFuture.supplyAsync(reverifier::run);
        assertThat(checking.await(30, TimeUnit.SECONDS)).isTrue();
        Optional<Run> second = reverifier.run();
        secondFinished.countDown();

        assertThat(second).isEmpty();
        assertThat(first.get(30, TimeUnit.SECONDS))
                .hasValueSatisfying(
                        run -> assertThat(run.checked()).containsOnly(Map.entry(ReverifyOutcome.Kind.CONFIRMED, 1)));
        assertThat(github.requests(GitHubApiStub.collaboratorPermissionPath(repoId, VERIFIER)))
                .hasSize(1);
        assertThat(link(appId).status()).isEqualTo("ACTIVE");
    }

    @Test
    void anOrgWithManyOldLinksSharesTurnsWithAnOrgWithOneNewerLink() {
        String big = orgOn(installationId);
        String small = orgOn(installationId);
        List<Long> bigRepos = List.of(newRepoId(), newRepoId(), newRepoId());
        for (int i = 0; i < bigRepos.size(); i++) {
            link(big, installationId, bigRepos.get(i), VERIFIER_ID, VERIFIER, DUE.plusHours(10 - i));
            github.stubCollaboratorPermission(bigRepos.get(i), VERIFIER, VERIFIER_ID, "write", "write");
        }
        link(small, installationId, repoId, VERIFIER_ID, VERIFIER, DUE);
        github.stubCollaboratorPermission(repoId, VERIFIER, VERIFIER_ID, "write", "write");

        reverifier.run().orElseThrow();

        assertThat(github.allRequests().stream()
                        .filter(request -> request.getUrl().contains("/collaborators/"))
                        .sorted(Comparator.comparing(LoggedRequest::getLoggedDate))
                        .map(LoggedRequest::getUrl))
                .containsExactly(
                        GitHubApiStub.collaboratorPermissionPath(bigRepos.get(0), VERIFIER),
                        GitHubApiStub.collaboratorPermissionPath(repoId, VERIFIER),
                        GitHubApiStub.collaboratorPermissionPath(bigRepos.get(1), VERIFIER),
                        GitHubApiStub.collaboratorPermissionPath(bigRepos.get(2), VERIFIER));
    }

    @Test
    void oneMetadataTokenServesEveryRepositoryOnTheInstallation() {
        String orgId = orgOn(installationId);
        long otherRepo = newRepoId();
        link(orgId, installationId, repoId, VERIFIER_ID, VERIFIER, DUE);
        link(orgId, installationId, otherRepo, VERIFIER_ID, VERIFIER, DUE);
        github.stubCollaboratorPermission(repoId, VERIFIER, VERIFIER_ID, "write", "write");
        github.stubCollaboratorPermission(otherRepo, VERIFIER, VERIFIER_ID, "write", "write");

        reverifier.run().orElseThrow();

        assertThat(github.mintRequests(installationId)).singleElement().satisfies(mint -> {
            JsonNode body = JSON.readTree(mint.getBodyAsString());
            assertThat(body.has("repository_ids")).isFalse();
            assertThat(body.path("permissions").path("metadata").asString()).isEqualTo("read");
        });
    }

    @Test
    void aLinkCheckedRecentlyIsNotDueYet() {
        UUID appId = link(orgOn(installationId), installationId, repoId, VERIFIER_ID, VERIFIER, Duration.ofHours(1));
        github.stubCollaboratorPermission(repoId, VERIFIER, VERIFIER_ID, "read", "read");

        reverifier.run().orElseThrow();

        assertThat(github.requests(GitHubApiStub.collaboratorPermissionPath(repoId, VERIFIER)))
                .isEmpty();
        assertThat(link(appId).status()).isEqualTo("ACTIVE");
    }

    @Test
    void theOldestCheckGaugeIsTheAgeOfTheLeastRecentlyCheckedActiveLink() {
        String orgId = orgOn(installationId);
        link(orgId, installationId, repoId, VERIFIER_ID, VERIFIER, Duration.ofHours(30));
        link(orgId, installationId, newRepoId(), VERIFIER_ID, VERIFIER, Duration.ofHours(60));

        metrics.refresh();

        assertThat(meters.get(ReverifyMetrics.OLDEST_CHECK_AGE).gauge().value())
                .isCloseTo(Duration.ofHours(60).toSeconds(), within(60.0));
    }

    private String orgOn(long installation) {
        String orgId = fixtures.newOrg();
        fixtures.linkInstallation(installation, orgId, "ACTIVE");
        return orgId;
    }

    private UUID link(String orgId, long installation, long repo, long githubUserId, String login, Duration age) {
        UUID appId = fixtures.newRepoLink(orgId, installation, repo);
        jdbc.update("""
                UPDATE git_integration.repo_links
                   SET verified_github_user_id = ?, verified_github_login = ?,
                       access_checked_at = now() - make_interval(secs => ?)
                 WHERE app_id = ?
                """, githubUserId, login, (double) age.toSeconds(), appId);
        return appId;
    }

    private record Row(
            String status, String reason, String login, String permission, Instant checkedAt, long version) {}

    private Row link(UUID appId) {
        return jdbc.queryForObject(
                """
                SELECT status, disconnect_reason, verified_github_login, verified_permission, access_checked_at, version
                  FROM git_integration.repo_links WHERE app_id = ?
                """,
                (row, i) -> new Row(
                        row.getString("status"),
                        row.getString("disconnect_reason"),
                        row.getString("verified_github_login"),
                        row.getString("verified_permission"),
                        row.getTimestamp("access_checked_at").toInstant(),
                        row.getLong("version")),
                appId);
    }

    private void head(UUID appId, String sha) {
        jdbc.update(
                "INSERT INTO git_integration.branch_heads (app_id, branch, head_sha) VALUES (?, 'main', ?)",
                appId,
                sha);
    }

    private List<String> head(UUID appId) {
        return jdbc.queryForList(
                "SELECT head_sha FROM git_integration.branch_heads WHERE app_id = ?", String.class, appId);
    }

    private List<JsonNode> notices(String orgId) {
        return outbox(orgId, NotificationRequested.TYPE);
    }

    private List<JsonNode> audits(String orgId, String action) {
        return outbox(orgId, AuditEventRecorded.TYPE).stream()
                .filter(audit -> action.equals(audit.path("action").asString()))
                .toList();
    }

    private int pushes(String orgId) {
        return outbox(orgId, GitPushReceived.TYPE).size();
    }

    private List<JsonNode> outbox(String orgId, String eventType) {
        return jdbc.query("""
                SELECT payload::text AS payload FROM git_integration.outbox_events
                 WHERE org_id = ? AND event_type = ? ORDER BY id
                """, (row, i) -> JSON.readTree(row.getString("payload")), orgId, eventType);
    }

    private void postAndProcess(WebhookFixtures.Delivery delivery) {
        assertThat(delivery.post(mvc).getStatus()).as("webhook acknowledgement").isEqualTo(202);
        UUID id = UUID.fromString(delivery.deliveryId());
        deliveries.add(id);
        for (int cycle = 0; cycle < MAX_CYCLES && "RECEIVED".equals(deliveryStatus(id)); cycle++) {
            processor.processBatch();
        }
        assertThat(deliveryStatus(id)).isNotEqualTo("RECEIVED");
    }

    private String deliveryStatus(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM git_integration.webhook_deliveries WHERE delivery_id = ?", String.class, id);
    }

    private double checked(String outcome) {
        return meters.counter(ReverifyMetrics.CHECKED, "outcome", outcome).count();
    }

    private static long newRepoId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the second run");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
