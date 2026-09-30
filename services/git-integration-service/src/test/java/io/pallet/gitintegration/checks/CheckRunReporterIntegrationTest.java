package io.pallet.gitintegration.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.observability.MetricsCatalog;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class})
class CheckRunReporterIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long CHECK_RUN_ID = 9001;
    private static final int SLOW_WRITE_MILLIS = 400;

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private CheckRunReporter reporter;

    @Autowired
    private DesiredStateWriter writer;

    @Autowired
    private CheckRunRepository checkRuns;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ReadModelFixtures fixtures;
    private TransactionTemplate transaction;
    private long installationId;
    private String orgId;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("UPDATE git_integration.check_runs SET reported_revision = desired_revision");
        installationId = fixtures.newInstallation();
        orgId = fixtures.newOrg();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        github.stubTokenMint(installationId);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void theFirstReportCreatesTheCheckRunAndTheNextUpdatesIt() {
        Linked app = link();
        String sha = newSha();
        desire(app, sha, writer.buildStarted());
        github.stubCheckRunCreated(app.repoId(), CHECK_RUN_ID, 0);
        double createdBefore = reported("created");

        reporter.report().orElseThrow();

        JsonNode created = body(single(github.requests(GitHubApiStub.checkRunsPath(app.repoId()))));
        assertThat(created.path("name").asString()).isEqualTo("Pallet / " + app.slug());
        assertThat(created.path("head_sha").asString()).isEqualTo(sha);
        assertThat(created.path("external_id").asString()).isEqualTo(app.appId().toString());
        assertThat(created.path("status").asString()).isEqualTo("in_progress");
        assertThat(created.has("conclusion")).isFalse();
        assertThat(created.path("output").path("summary").asString()).isEqualTo("Building");
        JsonNode mint = body(github.mintRequests(installationId).getFirst());
        assertThat(mint.path("repository_ids").get(0).asLong()).isEqualTo(app.repoId());
        assertThat(mint.path("permissions").path("checks").asString()).isEqualTo("write");
        CheckRun first = row(app, sha);
        assertThat(first.checkRunId()).isEqualTo(CHECK_RUN_ID);
        assertThat(first.pending()).isFalse();
        assertThat(first.lastReportedState()).isEqualTo(CheckState.IN_PROGRESS);
        assertThat(reported("created") - createdBefore).isEqualTo(1);

        desire(app, sha, writer.buildSucceeded());
        github.stubCheckRunUpdated(app.repoId(), CHECK_RUN_ID);

        reporter.report().orElseThrow();

        JsonNode updated = body(single(github.requests(GitHubApiStub.checkRunPath(app.repoId(), CHECK_RUN_ID))));
        assertThat(updated.path("status").asString()).isEqualTo("completed");
        assertThat(updated.path("conclusion").asString()).isEqualTo("success");
        assertThat(updated.path("name").asString()).isEqualTo("Pallet / " + app.slug());
        assertThat(github.requests(GitHubApiStub.checkRunsPath(app.repoId()))).hasSize(1);
        CheckRun second = row(app, sha);
        assertThat(second.pending()).isFalse();
        assertThat(second.lastReportedState()).isEqualTo(CheckState.COMPLETED);
    }

    @Test
    void aCreateWhoseIdWasNeverStoredIsFoundInsteadOfCreatedTwice() {
        Linked app = link();
        String sha = newSha();
        desire(app, sha, writer.buildStarted());
        jdbc.update("UPDATE git_integration.check_runs SET attempts = 1, check_run_id = NULL WHERE org_id = ?", orgId);
        github.stubCheckRunsForCommit(
                app.repoId(),
                sha,
                "[{\"id\":" + CHECK_RUN_ID + ",\"name\":\"Pallet / " + app.slug() + "\",\"head_sha\":\"" + sha
                        + "\",\"external_id\":\"" + app.appId() + "\"}]");
        github.stubCheckRunUpdated(app.repoId(), CHECK_RUN_ID);
        double recoveredBefore = reported("recovered");

        reporter.report().orElseThrow();

        assertThat(github.requests(GitHubApiStub.checkRunsPath(app.repoId()))).isEmpty();
        assertThat(github.requests(GitHubApiStub.checkRunPath(app.repoId(), CHECK_RUN_ID)))
                .hasSize(1);
        LoggedRequest lookup = single(github.requests(GitHubApiStub.commitCheckRunsPath(app.repoId(), sha)));
        assertThat(lookup.queryParameter("check_name").firstValue()).isEqualTo("Pallet / " + app.slug());
        assertThat(row(app, sha).checkRunId()).isEqualTo(CHECK_RUN_ID);
        assertThat(row(app, sha).pending()).isFalse();
        assertThat(reported("recovered") - recoveredBefore).isEqualTo(1);
    }

    @Test
    void aDesireArrivingDuringTheCallStaysPendingAndIsSentNextCycle() {
        Linked app = link();
        String sha = newSha();
        desire(app, sha, writer.buildStarted());
        github.stubCheckRunCreated(app.repoId(), CHECK_RUN_ID, 0);
        github.beforeAnswering(
                GitHubApiStub.checkRunsPath(app.repoId()), () -> desire(app, sha, writer.buildSucceeded()));

        reporter.report().orElseThrow();

        CheckRun afterFirst = row(app, sha);
        assertThat(afterFirst.checkRunId()).isEqualTo(CHECK_RUN_ID);
        assertThat(afterFirst.lastReportedState()).isEqualTo(CheckState.IN_PROGRESS);
        assertThat(afterFirst.desired().state()).isEqualTo(CheckState.COMPLETED);
        assertThat(afterFirst.pending()).isTrue();

        github.stubCheckRunUpdated(app.repoId(), CHECK_RUN_ID);

        reporter.report().orElseThrow();

        JsonNode updated = body(single(github.requests(GitHubApiStub.checkRunPath(app.repoId(), CHECK_RUN_ID))));
        assertThat(updated.path("status").asString()).isEqualTo("completed");
        assertThat(row(app, sha).pending()).isFalse();
    }

    @Test
    void aFailingGitHubIsNotAskedTwiceForOneCreateBacksOffAndLaterSucceeds() {
        Linked app = link();
        String sha = newSha();
        desire(app, sha, writer.buildStarted());
        github.stub5xx(GitHubApiStub.checkRunsPath(app.repoId()));
        double unavailableBefore = failures("github_unavailable");

        reporter.report().orElseThrow();

        assertThat(github.requests(GitHubApiStub.checkRunsPath(app.repoId())))
                .as("a create is never repeated inside one call")
                .hasSize(1);
        CheckRun failed = row(app, sha);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.pending()).isTrue();
        assertThat(nextAttemptAt(app, sha)).isAfter(Instant.now().minusSeconds(1));
        assertThat(failures("github_unavailable") - unavailableBefore).isEqualTo(1);

        github.server().resetAll();
        github.stubTokenMint(installationId);
        github.stubCheckRunsForCommit(app.repoId(), sha, "[]");
        github.stubCheckRunCreated(app.repoId(), CHECK_RUN_ID, 0);
        jdbc.update("UPDATE git_integration.check_runs SET next_attempt_at = now() WHERE org_id = ?", orgId);

        reporter.report().orElseThrow();

        assertThat(github.requests(GitHubApiStub.commitCheckRunsPath(app.repoId(), sha)))
                .hasSize(1);
        assertThat(github.requests(GitHubApiStub.checkRunsPath(app.repoId()))).hasSize(1);
        CheckRun reported = row(app, sha);
        assertThat(reported.checkRunId()).isEqualTo(CHECK_RUN_ID);
        assertThat(reported.pending()).isFalse();
        assertThat(reported.attempts()).isZero();
    }

    @Test
    void aRateLimitWaitsForTheResetWithoutSpendingAnAttempt() {
        Linked app = link();
        String sha = newSha();
        desire(app, sha, writer.buildStarted());
        Instant reset = Instant.now().plus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.SECONDS);
        github.stubRateLimited(GitHubApiStub.checkRunsPath(app.repoId()), reset);

        reporter.report().orElseThrow();

        CheckRun waiting = row(app, sha);
        assertThat(waiting.attempts()).isZero();
        assertThat(waiting.pending()).isTrue();
        assertThat(nextAttemptAt(app, sha)).isCloseTo(reset, within(10, ChronoUnit.SECONDS));
    }

    @Test
    void twoAppsOnOneInstallationAreWrittenOneAtATime() {
        Linked first = link();
        Linked second = link();
        String sha = newSha();
        desire(first, sha, writer.buildStarted());
        desire(second, sha, writer.buildStarted());
        github.stubCheckRunCreated(first.repoId(), 1, SLOW_WRITE_MILLIS);
        github.stubCheckRunCreated(second.repoId(), 2, SLOW_WRITE_MILLIS);

        reporter.report().orElseThrow();

        List<Instant> writes = github.allRequests().stream()
                .filter(request -> request.getUrl().endsWith("/check-runs"))
                .map(request -> request.getLoggedDate().toInstant())
                .sorted(Comparator.naturalOrder())
                .toList();
        assertThat(writes).hasSize(2);
        assertThat(Duration.between(writes.get(0), writes.get(1)))
                .as("the second write starts only after the first was answered")
                .isGreaterThanOrEqualTo(Duration.ofMillis(SLOW_WRITE_MILLIS));
        assertThat(row(first, sha).pending()).isFalse();
        assertThat(row(second, sha).pending()).isFalse();
    }

    @Test
    void aCheckRunWhoseLinkWasDisconnectedIsDroppedWithoutCallingGitHub() {
        Linked app = link();
        String sha = newSha();
        desire(app, sha, writer.buildStarted());
        jdbc.update(
                "UPDATE git_integration.repo_links SET status = 'DISCONNECTED', disconnect_reason = 'UNLINKED_BY_USER',"
                        + " disconnected_at = now() WHERE app_id = ?",
                app.appId());
        double droppedBefore = dropped("disconnected");

        reporter.report().orElseThrow();

        assertThat(github.totalRequests()).isZero();
        CheckRun dropped = row(app, sha);
        assertThat(dropped.pending()).isFalse();
        assertThat(dropped.checkRunId()).isNull();
        assertThat(dropped("disconnected") - droppedBefore).isEqualTo(1);
    }

    @Test
    void aCheckRunThatFailsEveryAttemptIsGivenUp() {
        Linked app = link();
        String sha = newSha();
        desire(app, sha, writer.buildStarted());
        jdbc.update("UPDATE git_integration.check_runs SET attempts = 19 WHERE org_id = ?", orgId);
        github.stubCheckRunsForCommit(app.repoId(), sha, "[]");
        github.stubNotFound(GitHubApiStub.checkRunsPath(app.repoId()));
        double givenUpBefore = dropped("max_attempts");

        reporter.report().orElseThrow();

        assertThat(row(app, sha).pending()).isFalse();
        assertThat(dropped("max_attempts") - givenUpBefore).isEqualTo(1);
    }

    private Linked link() {
        long repoId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        UUID appId = fixtures.newRepoLink(orgId, installationId, repoId);
        String slug =
                jdbc.queryForObject("SELECT slug FROM git_integration.apps WHERE app_id = ?", String.class, appId);
        return new Linked(appId, repoId, slug);
    }

    private void desire(Linked app, String sha, DesiredCheck desired) {
        transaction.executeWithoutResult(status -> writer.apply(orgId, app.appId(), sha, desired));
    }

    private CheckRun row(Linked app, String sha) {
        return checkRuns.find(orgId, app.appId(), sha).orElseThrow();
    }

    private Instant nextAttemptAt(Linked app, String sha) {
        return jdbc.queryForObject(
                        "SELECT next_attempt_at FROM git_integration.check_runs WHERE app_id = ? AND commit_sha = ?",
                        Timestamp.class,
                        app.appId(),
                        sha)
                .toInstant();
    }

    private double reported(String outcome) {
        return count(meters.find(MetricsCatalog.CHECKS_REPORTED)
                .tag("outcome", outcome)
                .counter());
    }

    private double failures(String kind) {
        return count(
                meters.find(MetricsCatalog.CHECKS_FAILURES).tag("kind", kind).counter());
    }

    private double dropped(String reason) {
        return count(
                meters.find(MetricsCatalog.CHECKS_DROPPED).tag("reason", reason).counter());
    }

    private static double count(Counter counter) {
        return counter == null ? 0 : counter.count();
    }

    private static LoggedRequest single(List<LoggedRequest> requests) {
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }

    private static JsonNode body(LoggedRequest request) {
        return JSON.readTree(request.getBodyAsString());
    }

    private static String newSha() {
        byte[] bytes = new byte[20];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record Linked(UUID appId, long repoId, String slug) {}
}
