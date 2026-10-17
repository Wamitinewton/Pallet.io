package io.pallet.buildqueue.lifecycle;

import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.dispatch.DispatchWakeups;
import io.pallet.buildqueue.dispatch.Dispatcher;
import io.pallet.buildqueue.support.StubBuilder;
import io.pallet.buildqueue.support.StubBuilder.Mode;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The whole loop inside the service: push, intake, dispatch, a stub build-service's results, and the next dispatch
 * woken by each freed slot. The wakeup loop runs for the test only (see {@link DispatchWakeups}).
 */
@IntegrationTest
@Import({RedisTestContainerConfiguration.class, StubBuilder.class})
@TestPropertySource(
        properties = "pallet.build-queue.dispatch.global-concurrency=" + DispatchLoopIntegrationTest.CAPACITY)
class DispatchLoopIntegrationTest {

    static final int CAPACITY = 2;

    /** Mapped to the {@code pair} profile (max-concurrent 2) in application-test.yml. */
    private static final String ORG = "org-dispatch-loop";

    private static final int APPS = 5;
    private static final int PUSHES_PER_APP = 2;
    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(10);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private BuildRepository builds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Dispatcher dispatcher;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private StubBuilder stubBuilder;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private ApplicationContext context;

    private LifecycleDriver driver;

    @BeforeEach
    void setUp() {
        driver = new LifecycleDriver(publisher, builds, jdbc, dispatcher, listeners, kafka.getBootstrapServers());
        driver.quiesce();
        stubBuilder.start(Mode.normal());
        DispatchWakeups.of(context).start();
    }

    @AfterEach
    void tearDown() {
        DispatchWakeups.of(context).stop();
        stubBuilder.stop();
        driver.quiesce();
        driver.close();
    }

    @Test
    void everyAppEndsOnItsNewestCommitWithoutEverExceedingALimit() throws InterruptedException {
        List<String> apps = IntStream.range(0, APPS)
                .mapToObj(i -> "app-" + UUID.randomUUID())
                .toList();
        List<List<GitPushReceived>> pushesByApp = apps.stream()
                .map(app -> IntStream.range(0, PUSHES_PER_APP)
                        .mapToObj(i -> push(ORG, app).build())
                        .toList())
                .toList();
        InFlightSampler sampler = new InFlightSampler();
        Thread sampling = Thread.ofVirtual().start(sampler);

        try {
            for (int commit = 0; commit < PUSHES_PER_APP; commit++) {
                for (List<GitPushReceived> pushes : pushesByApp) {
                    driver.publish(pushes.get(commit));
                }
            }
            for (List<GitPushReceived> pushes : pushesByApp) {
                driver.awaitStatus(pushes.getLast(), BuildStatus.SUCCEEDED);
            }
        } finally {
            sampler.stop();
            sampling.join();
        }

        for (List<GitPushReceived> pushes : pushesByApp) {
            for (GitPushReceived older : pushes.subList(0, pushes.size() - 1)) {
                assertThat(driver.build(older).status()).isIn(BuildStatus.SUCCEEDED, BuildStatus.SUPERSEDED);
            }
        }
        assertThat(sampler.samples()).isPositive();
        assertThat(sampler.mostInFlightForOneApp()).isOne();
        assertThat(sampler.mostInFlight()).isEqualTo(CAPACITY);
    }

    /** Reads the org's in-flight builds until stopped, keeping the highest totals seen. */
    private final class InFlightSampler implements Runnable {

        private volatile boolean running = true;
        private final AtomicInteger samples = new AtomicInteger();
        private final AtomicInteger mostInFlight = new AtomicInteger();
        private final AtomicInteger mostInFlightForOneApp = new AtomicInteger();

        @Override
        public void run() {
            while (running) {
                List<String> inFlight = jdbc.queryForList(
                        "SELECT app_id FROM build_queue.builds WHERE org_id = ? AND status IN ('DISPATCHED', 'RUNNING')",
                        String.class,
                        ORG);
                Map<String, Long> perApp =
                        inFlight.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
                samples.incrementAndGet();
                mostInFlight.accumulateAndGet(inFlight.size(), Math::max);
                perApp.values().forEach(count -> mostInFlightForOneApp.accumulateAndGet(count.intValue(), Math::max));
                try {
                    Thread.sleep(SAMPLE_INTERVAL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        void stop() {
            running = false;
        }

        int samples() {
            return samples.get();
        }

        int mostInFlight() {
            return mostInFlight.get();
        }

        int mostInFlightForOneApp() {
            return mostInFlightForOneApp.get();
        }
    }
}
