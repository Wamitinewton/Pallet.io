package io.pallet.gitintegration.installation;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.installation.RepositorySync.Result;
import io.pallet.gitintegration.installation.RepositorySyncScheduler.Run;
import io.pallet.gitintegration.support.GitHubApiStub;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class})
class RepositorySyncSchedulerIntegrationTest {

    private static final String PATH = GitHubApiStub.INSTALLATION_REPOSITORIES_PATH;

    /** Not in {@code github/api/installation-repositories.json}, which lists 42 to 46. */
    private static final long GONE = 99;

    private static final long LISTED = 42;

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private RepositorySyncScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    private LifecycleScenario scenario;
    private long installationId;
    private String orgA;
    private String orgB;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE git_integration.installations SET repositories_synced_at = 'infinity'"
                + " WHERE status = 'ACTIVE'");
        scenario = new LifecycleScenario(jdbc, processor);
        installationId = scenario.fixtures.newInstallation();
        orgA = scenario.orgLinkedTo(installationId);
        orgB = scenario.orgLinkedTo(installationId);
        github.stubTokenMint(installationId);
    }

    @AfterEach
    void tearDown() {
        scenario.cleanUp();
    }

    @Test
    void onlyOneOfTwoConcurrentRunsDoesAnyWork() throws Exception {
        github.stubInstallationRepositories();
        CountDownLatch listing = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        github.beforeAnswering(PATH, () -> {
            listing.countDown();
            await(secondFinished);
        });

        CompletableFuture<Optional<Run>> first = CompletableFuture.supplyAsync(scheduler::run);
        assertThat(listing.await(30, TimeUnit.SECONDS)).isTrue();
        Optional<Run> second = scheduler.run();
        secondFinished.countDown();

        assertThat(second).isEmpty();
        assertThat(first.get(30, TimeUnit.SECONDS))
                .hasValueSatisfying(run -> assertThat(run.results()).containsEntry(Result.SYNCED, 1));
        assertThat(github.requests(PATH)).hasSize(1);
        assertThat(synced()).isNotNull();
    }

    @Test
    void aRepositoryMissingFromTheListingIsDisconnectedInEveryOrgAndNotified() {
        UUID goneA = scenario.fixtures.newRepoLink(orgA, installationId, GONE);
        UUID goneB = scenario.fixtures.newRepoLink(orgB, installationId, GONE);
        UUID listed = scenario.fixtures.newRepoLink(orgA, installationId, LISTED);
        github.stubInstallationRepositories();

        Run run = scheduler.run().orElseThrow();

        assertThat(run.results()).containsEntry(Result.SYNCED, 1);
        assertThat(scenario.repoLink(goneA)).containsEntry("disconnect_reason", "REPOSITORY_ACCESS_REMOVED");
        assertThat(scenario.repoLink(goneB)).containsEntry("disconnect_reason", "REPOSITORY_ACCESS_REMOVED");
        assertThat(scenario.repoLink(listed)).containsEntry("status", "ACTIVE");
        assertThat(scenario.notices(orgA)).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(ConnectionLostNotifier.NOTIFICATION_TYPE);
            assertThat(notice.dedupeKey()).isEqualTo("git-connection-lost:" + run.runId() + ":" + orgA + ":" + GONE);
            assertThat(notice.variables().path("reason").asString()).isEqualTo("REPOSITORY_ACCESS_REMOVED");
            assertThat(notice.appSlugs()).containsExactly(scenario.slug(goneA));
        });
        assertThat(scenario.notices(orgB))
                .singleElement()
                .satisfies(notice -> assertThat(notice.appSlugs()).containsExactly(scenario.slug(goneB)));
    }

    @Test
    void anInstallationUnderItsBudgetReserveIsSkipped() {
        github.server()
                .stubFor(get(urlPathEqualTo(PATH))
                        .willReturn(GitHubApiStub.fixture("installation-repositories.json")
                                .withHeader("x-ratelimit-remaining", "100")
                                .withHeader(
                                        "x-ratelimit-reset",
                                        String.valueOf(
                                                Instant.now().plusSeconds(3_600).getEpochSecond()))));
        assertThat(scheduler.run().orElseThrow().results()).containsEntry(Result.SYNCED, 1);

        assertThat(scheduler.run().orElseThrow().results()).containsEntry(Result.SKIPPED_BUDGET, 1);

        assertThat(github.requests(PATH)).hasSize(1);
    }

    private Object synced() {
        return jdbc.queryForObject(
                "SELECT repositories_synced_at FROM git_integration.installations WHERE installation_id = ?",
                Object.class,
                installationId);
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
