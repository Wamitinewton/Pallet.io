package io.pallet.buildqueue.dispatch;

import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.dispatch.Dispatcher.Round;
import io.pallet.buildqueue.dispatch.Dispatcher.Round.Outcome;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Round robin over orgs with waiting work: a deep backlog in one org never puts another org's build behind it. */
@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class FairnessIntegrationTest {

    /** Mapped to the {@code wide} profile (max-concurrent 20) in application-test.yml. */
    private static final String BACKLOGGED = "org-fairness-wide";

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

    private DispatchDriver driver;

    @BeforeEach
    void setUp() {
        driver = new DispatchDriver(publisher, builds, jdbc, jsonMapper);
        driver.quiesce();
    }

    @AfterEach
    void tearDown() {
        driver.quiesce();
    }

    @Test
    void aBackloggedOrgIsServedOnlyWhenNoOtherOrgIsWaiting() {
        String b = "org-fairness-b-" + UUID.randomUUID();
        String c = "org-fairness-c-" + UUID.randomUUID();
        String d = "org-fairness-d-" + UUID.randomUUID();
        List<GitPushReceived> backlog = IntStream.range(0, 20)
                .mapToObj(i -> push(BACKLOGGED, newApp()).build())
                .toList();
        driver.queue(backlog.toArray(GitPushReceived[]::new));
        driver.queue(
                push(b, newApp()).build(),
                push(c, newApp()).build(),
                push(d, newApp()).build());
        driver.forgetTurn(BACKLOGGED);

        assertThat(serveRound()).containsExactlyInAnyOrder(BACKLOGGED, b, c, d);

        List<String> served = new ArrayList<>();
        served.add(releaseAndServe(b));
        served.add(releaseAndServe(c));
        driver.queue(push(c, newApp()).build());
        served.add(releaseAndServe(d));
        served.add(releaseAndServe(BACKLOGGED));
        driver.queue(push(b, newApp()).build());
        served.add(releaseAndServe(BACKLOGGED));
        for (int release = 0; release < 5; release++) {
            served.add(releaseAndServe(BACKLOGGED));
        }

        assertThat(served)
                .containsExactly(
                        BACKLOGGED,
                        BACKLOGGED,
                        c,
                        BACKLOGGED,
                        b,
                        BACKLOGGED,
                        BACKLOGGED,
                        BACKLOGGED,
                        BACKLOGGED,
                        BACKLOGGED);
    }

    /** Frees one of {@code orgId}'s slots, runs a round, and returns the one org it served. */
    private String releaseAndServe(String orgId) {
        driver.finish(driver.dispatchedOf(orgId).getFirst());
        List<String> served = serveRound();
        assertThat(served).as("one slot freed, one build dispatched").hasSize(1);
        return served.getFirst();
    }

    private List<String> serveRound() {
        Map<UUID, String> before = driver.dispatched();
        Round round = dispatcher.runRound();
        assertThat(round.outcome()).isEqualTo(Outcome.DISPATCHED);
        return driver.dispatched().entrySet().stream()
                .filter(entry -> !before.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    private static String newApp() {
        return "app-" + UUID.randomUUID();
    }
}
