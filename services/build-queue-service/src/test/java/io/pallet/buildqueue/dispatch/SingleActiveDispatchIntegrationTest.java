package io.pallet.buildqueue.dispatch;

import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.config.SchedulingLocks.Job;
import io.pallet.buildqueue.dispatch.Dispatcher.Round.Outcome;
import io.pallet.common.events.BuildDispatched;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Every instance runs rounds; the per-round advisory lock lets one dispatch at a time. */
@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class SingleActiveDispatchIntegrationTest {

    private static final int ORGS = 10;
    private static final int BRANCHES_PER_APP = 5;
    private static final int ROUNDS_PER_RACER = 100;
    private static final long STEP_TIMEOUT_SECONDS = 30;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private BuildRepository builds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private Dispatcher dispatcher;

    @Autowired
    private TransactionTemplate transaction;

    private DispatchDriver driver;
    private ExecutorService racers;

    @BeforeEach
    void setUp() {
        driver = new DispatchDriver(publisher, builds, jdbc, jsonMapper);
        driver.quiesce();
        racers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        racers.shutdownNow();
        driver.quiesce();
    }

    @Test
    void twoRacingDispatchersDispatchEveryBuildExactlyOnce() throws Exception {
        List<String> orgIds = IntStream.range(0, ORGS)
                .mapToObj(i -> "org-race-" + UUID.randomUUID())
                .toList();
        List<GitPushReceived> pushes = new ArrayList<>();
        for (String orgId : orgIds) {
            String appId = "app-" + UUID.randomUUID();
            for (int branch = 0; branch < BRANCHES_PER_APP; branch++) {
                pushes.add(push(orgId, appId).branch("branch-" + branch).build());
            }
        }
        driver.queue(pushes.toArray(GitPushReceived[]::new));

        CyclicBarrier start = new CyclicBarrier(2);
        Callable<List<Outcome>> racer = () -> {
            List<Outcome> outcomes = new ArrayList<>();
            for (int round = 0; round < ROUNDS_PER_RACER; round++) {
                start.await(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                outcomes.add(dispatcher.runRound().outcome());
                driver.finishAllDispatched();
            }
            return outcomes;
        };
        Future<List<Outcome>> first = racers.submit(racer);
        Future<List<Outcome>> second = racers.submit(racer);
        List<Outcome> outcomes = new ArrayList<>(first.get(2, TimeUnit.MINUTES));
        outcomes.addAll(second.get(2, TimeUnit.MINUTES));

        assertThat(outcomes).contains(Outcome.LOCK_BUSY);
        for (GitPushReceived push : pushes) {
            assertThat(driver.build(push).attempt())
                    .as("attempt of %s", push.branch())
                    .isOne();
        }
        Map<String, Long> dispatchesPerBuild = new HashMap<>();
        for (String orgId : orgIds) {
            driver.dispatchedEvents(orgId)
                    .forEach(event -> dispatchesPerBuild.merge(DispatchDriver.text(event, "buildId"), 1L, Long::sum));
        }
        assertThat(dispatchesPerBuild).hasSize(pushes.size());
        assertThat(dispatchesPerBuild.values()).containsOnly(1L);
    }

    @Test
    void aRoundStartedWhileAnotherHoldsTheLockSkipsWithoutTouchingAnything() {
        GitPushReceived event =
                push("org-" + UUID.randomUUID(), "app-" + UUID.randomUUID()).build();
        driver.queue(event);

        Outcome outcome = transaction.execute(status -> {
            jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, Job.DISPATCH.key());
            try {
                return racers.submit(() -> dispatcher.runRound().outcome()).get(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(outcome).isEqualTo(Outcome.LOCK_BUSY);
        assertThat(driver.status(event)).isEqualTo(BuildStatus.QUEUED);
        assertThat(driver.dispatchedEvents(event.orgId())).isEmpty();
        assertThat(dispatcher.runRound().outcome()).isEqualTo(Outcome.DISPATCHED);
        assertThat(driver.dispatchedEvents(event.orgId()))
                .singleElement()
                .satisfies(dispatched ->
                        assertThat(DispatchDriver.text(dispatched, "eventType")).isEqualTo(BuildDispatched.TYPE));
    }
}
