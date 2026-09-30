package io.pallet.gitintegration.push;

import static io.pallet.gitintegration.push.PushScenario.sha;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.push.PushScenario.Published;
import io.pallet.gitintegration.support.GitHubApiStub;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class})
class PushOrderingIntegrationTest {

    private static final String ROOT = sha('0');
    private static final String A = sha('a');
    private static final String B = sha('b');
    private static final String C = sha('c');
    private static final Duration CONCURRENT_DEADLINE = Duration.ofSeconds(60);
    private static final Duration IDLE = Duration.ofMillis(20);

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    private PushScenario scenario;
    private String orgId;
    private UUID appId;

    @BeforeEach
    void setUp() {
        scenario = new PushScenario(mvc, jdbc, processor);
        orgId = scenario.newOrg();
        appId = scenario.linkApp(orgId);
        github.stubTokenMint(scenario.installationId);
    }

    @AfterEach
    void tearDown() {
        scenario.cleanUp();
    }

    @Test
    void pushesDeliveredOutOfOrderPublishOnlyWhatMovesTheHeadForward() {
        github.stubCompare(scenario.repoId, A, C, "ahead");
        github.stubCompare(scenario.repoId, C, B, "behind");

        Map<String, Object> first = scenario.postAndProcess(scenario.push(ROOT, A));
        Map<String, Object> third = scenario.postAndProcess(scenario.push(B, C));
        Map<String, Object> second = scenario.postAndProcess(scenario.push(A, B));

        assertThat(first).containsEntry("status", "PROCESSED");
        assertThat(third).containsEntry("status", "PROCESSED").containsEntry("attempts", 0);
        assertThat(second)
                .containsEntry("status", "IGNORED")
                .containsEntry("outcome_reason", PushProcessor.STALE)
                .containsEntry("attempts", 0);
        assertThat(scenario.published(orgId)).extracting(Published::commitSha).containsExactly(A, C);
        assertThat(scenario.head(appId)).contains(C);
        assertThat(github.requests(GitHubApiStub.comparePath(scenario.repoId, A, C)))
                .hasSize(1);
        assertThat(github.requests(GitHubApiStub.comparePath(scenario.repoId, C, B)))
                .hasSize(1);
        assertThat(github.mintRequests(scenario.installationId))
                .allSatisfy(mint -> assertThat(mint.getBodyAsString())
                        .contains("\"repository_ids\":[" + scenario.repoId + "]")
                        .contains("\"contents\":\"read\""));
    }

    @Test
    void historyRewrittenWithoutForceIsAccepted() {
        scenario.head(appId, A);
        github.stubCompare(scenario.repoId, A, C, "diverged");

        Map<String, Object> row = scenario.postAndProcess(scenario.push(B, C));

        assertThat(row).containsEntry("status", "PROCESSED");
        assertThat(scenario.head(appId)).contains(C);
        assertThat(scenario.published(orgId)).extracting(Published::commitSha).containsExactly(C);
    }

    @Test
    void anIdenticalCompareIsADuplicate() {
        scenario.head(appId, A);
        github.stubCompare(scenario.repoId, A, C, "identical");

        Map<String, Object> row = scenario.postAndProcess(scenario.push(B, C));

        assertThat(row).containsEntry("status", "IGNORED").containsEntry("outcome_reason", PushProcessor.DUPLICATE);
        assertThat(scenario.head(appId)).contains(A);
    }

    @Test
    void aPushOfACommitGitHubNoLongerHasIsStale() {
        scenario.head(appId, A);
        github.stubNotFound(GitHubApiStub.comparePath(scenario.repoId, A, C));
        github.stubNotFound(GitHubApiStub.commitPath(scenario.repoId, C));

        Map<String, Object> row = scenario.postAndProcess(scenario.push(B, C));

        assertThat(row).containsEntry("status", "IGNORED").containsEntry("outcome_reason", PushProcessor.STALE);
        assertThat(scenario.head(appId)).contains(A);
        assertThat(scenario.published(orgId)).isEmpty();
    }

    @Test
    void aHeadGitHubNoLongerHasWasRewrittenAwaySoThePushIsAccepted() {
        scenario.head(appId, A);
        github.stubNotFound(GitHubApiStub.comparePath(scenario.repoId, A, C));
        github.stubCommit(scenario.repoId, C);

        Map<String, Object> row = scenario.postAndProcess(scenario.push(B, C));

        assertThat(row).containsEntry("status", "PROCESSED");
        assertThat(scenario.head(appId)).contains(C);
    }

    @Test
    void aRateLimitedCompareWaitsForTheResetWithoutSpendingAnAttempt() {
        scenario.head(appId, A);
        Instant resetAt = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        github.stubRateLimited(GitHubApiStub.comparePath(scenario.repoId, A, C), resetAt);
        UUID delivery = scenario.post(scenario.push(B, C));

        Map<String, Object> limited = scenario.process(delivery);

        assertThat(limited).containsEntry("status", "RECEIVED").containsEntry("attempts", 0);
        assertThat(((Timestamp) limited.get("next_attempt_at")).toInstant()).isBetween(resetAt, resetAt.plusSeconds(5));
        assertThat((String) limited.get("last_error")).startsWith("GitHubRateLimitedException");
        assertThat(scenario.published(orgId)).isEmpty();

        github.stubCompare(scenario.repoId, A, C, "ahead");
        scenario.makeDue(delivery);
        Map<String, Object> retried = scenario.process(delivery);

        assertThat(retried).containsEntry("status", "PROCESSED").containsEntry("attempts", 0);
        assertThat(scenario.published(orgId)).extracting(Published::commitSha).containsExactly(C);
    }

    @RepeatedTest(5)
    void concurrentWorkersNeverAcceptOneCommitTwiceOrMoveTheHeadBack() throws Exception {
        scenario.head(appId, ROOT);
        github.stubCompare(scenario.repoId, ROOT, B, "ahead");
        github.stubCompare(scenario.repoId, B, A, "behind");
        List<UUID> deliveries = new ArrayList<>();
        for (int copy = 0; copy < 3; copy++) {
            deliveries.add(scenario.post(scenario.push(ROOT, A)));
            deliveries.add(scenario.post(scenario.push(A, B)));
        }
        Collections.shuffle(deliveries);
        deliveries.forEach(scenario::makeDue);

        runTwoWorkersUntilDone(deliveries);

        assertThat(deliveries)
                .allSatisfy(
                        id -> assertThat(scenario.delivery(id).get("status")).isIn("PROCESSED", "IGNORED"));
        List<String> published =
                scenario.published(orgId).stream().map(Published::commitSha).toList();
        assertThat(published).isIn(List.of(B), List.of(A, B));
        assertThat(scenario.head(appId)).contains(B);
    }

    private void runTwoWorkersUntilDone(List<UUID> deliveries) throws Exception {
        long deadline = System.nanoTime() + CONCURRENT_DEADLINE.toNanos();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                workers.add(pool.submit(() -> {
                    while (scenario.pending(deliveries) && System.nanoTime() < deadline) {
                        if (processor.processBatch() == 0) {
                            Thread.sleep(IDLE);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> worker : workers) {
                worker.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
