package io.pallet.gitintegration.recovery;

import static io.pallet.gitintegration.support.GitHubApiStub.LoggedDelivery.failed;
import static io.pallet.gitintegration.support.GitHubApiStub.LoggedDelivery.ok;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.recovery.RedeliverySweeper.Run;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.GitHubApiStub.LoggedDelivery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
@TestPropertySource(properties = "pallet.git.redelivery.max-per-run=" + RedeliverySweeperIntegrationTest.CAP)
class RedeliverySweeperIntegrationTest {

    static final int CAP = 3;

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        GitHubApiStub.register(registry);
    }

    @Autowired
    private RedeliverySweeper sweeper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final List<UUID> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM git_integration.sync_cursors WHERE name = ?", RedeliverySweeper.CURSOR);
    }

    @AfterEach
    void tearDown() {
        stored.forEach(id -> jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id));
        jdbc.update("DELETE FROM git_integration.sync_cursors WHERE name = ?", RedeliverySweeper.CURSOR);
    }

    @Test
    void onlyFailedDeliveriesThatAreNotStoredAreRedelivered() {
        String succeeded = guid();
        String storedLocally = store(guid());
        String lostOne = guid();
        String lostTwo = guid();
        String retriedThenOk = guid();
        String unsubscribed = guid();
        github.stubHookDeliveries(List.of(
                ok(108, retriedThenOk, minutesAgo(2), "push"),
                failed(107, lostTwo, minutesAgo(3), "push"),
                failed(106, lostTwo, minutesAgo(4), "push"),
                failed(105, unsubscribed, minutesAgo(5), "issues"),
                failed(104, retriedThenOk, minutesAgo(6), "push"),
                failed(103, lostOne, minutesAgo(7), "installation"),
                failed(102, storedLocally, minutesAgo(8), "push"),
                ok(101, succeeded, minutesAgo(9), "push")));
        github.stubRedeliveriesAccepted();
        double before = requestedCount();

        Run run = sweeper.run().orElseThrow();

        assertThat(github.redeliveryRequests()).containsExactly(103L, 107L);
        assertThat(run.requested()).isEqualTo(2);
        assertThat(run.failed()).isZero();
        assertThat(cursor()).contains(minutesAgo(2));
        assertThat(requestedCount() - before).isEqualTo(2);
    }

    @Test
    void theCapHoldsTheCursorAtTheOldestDeliveryNotYetRequested() {
        List<LoggedDelivery> log = new ArrayList<>();
        for (int i = 0; i < CAP + 2; i++) {
            log.addFirst(failed(200 + i, guid(), minutesAgo(20 - i), "push"));
        }
        github.stubHookDeliveries(log);
        github.stubRedeliveriesAccepted();

        Run run = sweeper.run().orElseThrow();

        assertThat(github.redeliveryRequests()).containsExactly(200L, 201L, 202L);
        assertThat(run.requested()).isEqualTo(CAP);
        assertThat(cursor()).contains(minutesAgo(20 - CAP));
    }

    @Test
    void aRequestGitHubDidNotTakeHoldsTheCursorAndIsTriedAgainNextRun() {
        String first = guid();
        String second = guid();
        github.stubHookDeliveries(List.of(
                failed(302, second, minutesAgo(3), "push"),
                failed(301, first, minutesAgo(4), "push"),
                ok(300, guid(), minutesAgo(5), "push")));
        github.stub5xx(GitHubApiStub.redeliveryPath(301));

        Run failedRun = sweeper.run().orElseThrow();

        assertThat(failedRun.requested()).isZero();
        assertThat(failedRun.failed()).isEqualTo(1);
        assertThat(github.redeliveryRequests()).doesNotContain(302L);
        assertThat(cursor()).contains(minutesAgo(4));

        github.server().resetRequests();
        github.stubRedeliveriesAccepted();
        Run retried = sweeper.run().orElseThrow();

        assertThat(retried.requested()).isEqualTo(2);
        assertThat(github.redeliveryRequests()).containsExactly(301L, 302L);
        assertThat(cursor()).contains(minutesAgo(3));
    }

    @Test
    void aDeliveryGitHubRefusesToRedeliverIsGivenUpOn() {
        String refused = guid();
        github.stubHookDeliveries(
                List.of(failed(402, guid(), minutesAgo(3), "push"), failed(401, refused, minutesAgo(4), "push")));
        github.stubRedeliveriesAccepted();
        github.stubNotFound(GitHubApiStub.redeliveryPath(401));

        Run run = sweeper.run().orElseThrow();

        assertThat(run.failed()).isEqualTo(1);
        assertThat(run.requested()).isEqualTo(1);
        assertThat(cursor()).contains(minutesAgo(3));
    }

    @Test
    void aSecondRunInsideTheWindowDoesNotRequestTheSameDeliveryAgain() {
        github.stubHookDeliveries(List.of(failed(501, guid(), minutesAgo(1), "push")));
        github.stubRedeliveriesAccepted();

        sweeper.run().orElseThrow();
        Run second = sweeper.run().orElseThrow();

        assertThat(second.requested()).isZero();
        assertThat(github.redeliveryRequests()).containsExactly(501L);
    }

    @Test
    void theLogIsReadPageByPageBackToTheCursor() {
        String older = guid();
        jdbc.update(
                "INSERT INTO git_integration.sync_cursors (name, cursor) VALUES (?, ?)",
                RedeliverySweeper.CURSOR,
                minutesAgo(30).toString());
        github.stubHookDeliveryPages(List.of(
                List.of(failed(603, guid(), minutesAgo(2), "push")),
                List.of(failed(602, older, minutesAgo(35), "push")),
                List.of(failed(601, guid(), minutesAgo(50), "push"))));
        github.stubRedeliveriesAccepted();

        sweeper.run().orElseThrow();

        assertThat(github.redeliveryRequests()).containsExactly(602L, 603L);
        assertThat(github.requests(GitHubApiStub.HOOK_DELIVERIES_PATH)).hasSize(3);
    }

    @Test
    void twoSweepersStartedTogetherLeaveTheWorkToOne() throws Exception {
        github.stubHookDeliveries(List.of(failed(701, guid(), minutesAgo(1), "push")));
        github.stubRedeliveriesAccepted();
        CountDownLatch listing = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        github.beforeAnswering(GitHubApiStub.HOOK_DELIVERIES_PATH, () -> {
            listing.countDown();
            await(secondFinished);
        });

        CompletableFuture<Optional<Run>> first = CompletableFuture.supplyAsync(sweeper::run);
        assertThat(listing.await(30, TimeUnit.SECONDS)).isTrue();
        Optional<Run> second = sweeper.run();
        secondFinished.countDown();

        assertThat(second).isEmpty();
        assertThat(first.get(30, TimeUnit.SECONDS))
                .hasValueSatisfying(run -> assertThat(run.requested()).isEqualTo(1));
        assertThat(github.redeliveryRequests()).containsExactly(701L);
    }

    private String store(String guid) {
        UUID id = UUID.fromString(guid);
        jdbc.update(
                "INSERT INTO git_integration.webhook_deliveries (delivery_id, event, status) VALUES (?, 'push', 'IGNORED')",
                id);
        stored.add(id);
        return guid;
    }

    private Optional<Instant> cursor() {
        return jdbc
                .queryForList(
                        "SELECT cursor FROM git_integration.sync_cursors WHERE name = ?",
                        String.class,
                        RedeliverySweeper.CURSOR)
                .stream()
                .findFirst()
                .map(Instant::parse);
    }

    private double requestedCount() {
        return meters.counter(RecoveryMetrics.REDELIVERY_REQUESTED).count();
    }

    private Instant minutesAgo(int minutes) {
        return now.minus(minutes, ChronoUnit.MINUTES);
    }

    private static String guid() {
        return new UUID(
                        ThreadLocalRandom.current().nextLong(),
                        ThreadLocalRandom.current().nextLong())
                .toString();
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
