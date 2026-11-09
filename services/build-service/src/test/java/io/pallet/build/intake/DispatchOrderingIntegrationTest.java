package io.pallet.build.intake;

import static io.pallet.build.support.Dispatches.dispatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pallet.build.domain.Build;
import io.pallet.build.domain.BuildRepository;
import io.pallet.build.domain.BuildStatus;
import io.pallet.build.support.ReadModelFixtures;
import io.pallet.common.events.BuildDispatched;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Two instances consuming the same build, through a rebalance or at the same instant, still record one row. */
@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class DispatchOrderingIntegrationTest {

    private static final int ATTEMPTS = 12;
    private static final int RACES = 20;
    private static final Duration ASSIGNMENT_WAIT = Duration.ofSeconds(60);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private BuildRepository builds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private ConcurrentKafkaListenerContainerFactory<String, JsonNode> containerFactory;

    @Autowired
    private DispatchListener listener;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private KafkaContainer kafka;

    private IntakeDriver driver;
    private ReadModelFixtures fixtures;

    @BeforeEach
    void setUp() {
        driver = new IntakeDriver(publisher, builds, jdbc, jsonMapper, listeners, kafka.getBootstrapServers());
        fixtures = new ReadModelFixtures(jdbc);
    }

    @AfterEach
    void tearDown() {
        driver.close();
        fixtures.cleanUp();
    }

    @Test
    void theBuildsPartitionMovingBetweenConsumersMidStreamLeavesOneRowAtTheLatestAttempt() {
        String orgId = fixtures.newOrg();
        BuildDispatched first =
                dispatch(orgId, fixtures.newApp(orgId, "ACTIVE")).build();
        List<BuildDispatched> attempts = new ArrayList<>();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            attempts.add(dispatch(orgId, first.appId())
                    .sameBuildAs(first)
                    .attempt(attempt)
                    .build());
        }
        MessageListenerContainer primary = listeners.getListenerContainer(DispatchListener.LISTENER_ID);
        ConcurrentMessageListenerContainer<String, JsonNode> second = secondConsumer(driver.groupId());

        try {
            attempts.subList(0, ATTEMPTS / 3).forEach(driver::publish);
            second.start();
            primary.stop();
            awaitAssigned(second);
            attempts.subList(ATTEMPTS / 3, 2 * ATTEMPTS / 3).forEach(driver::publish);
            primary.start();
            second.stop();
            awaitAssigned(primary);
            attempts.subList(2 * ATTEMPTS / 3, ATTEMPTS).forEach(driver::publish);

            driver.awaitAttempt(first, ATTEMPTS);
            driver.awaitProcessed();
        } finally {
            second.stop();
            primary.start();
        }

        assertThat(driver.rowCount(first.buildId())).isOne();
        Build build = driver.build(first);
        assertThat(build.status()).isEqualTo(BuildStatus.ACCEPTED);
        assertThat(build.attempt()).isEqualTo(ATTEMPTS);
        attempts.forEach(dispatch -> assertThat(driver.claims(dispatch)).isOne());
    }

    @Test
    void twoInstancesApplyingTheSameNewBuildAtOnceRecordOneRow() throws Exception {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "ACTIVE");
        ExecutorService instances = Executors.newFixedThreadPool(2);
        try {
            for (int race = 0; race < RACES; race++) {
                BuildDispatched one = dispatch(orgId, appId).build();
                BuildDispatched other = dispatch(orgId, appId).sameBuildAs(one).build();
                CyclicBarrier start = new CyclicBarrier(2);

                CompletableFuture.allOf(
                                CompletableFuture.runAsync(() -> apply(one, start), instances),
                                CompletableFuture.runAsync(() -> apply(other, start), instances))
                        .get();

                assertThat(driver.rowCount(one.buildId())).as("race %d", race).isOne();
                assertThat(driver.claims(one) + driver.claims(other)).isEqualTo(2);
                assertThat(driver.build(one).status()).isEqualTo(BuildStatus.ACCEPTED);
            }
        } finally {
            instances.shutdownNow();
        }
    }

    private void apply(BuildDispatched dispatch, CyclicBarrier start) {
        ConsumerRecord<String, JsonNode> record = new ConsumerRecord<>(
                Topics.BUILD_DISPATCHED, 0, 0L, dispatch.orgId(), jsonMapper.valueToTree(dispatch));
        try {
            start.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        transaction.executeWithoutResult(status -> listener.onMessage(record));
    }

    private static void awaitAssigned(MessageListenerContainer container) {
        await().atMost(ASSIGNMENT_WAIT).until(() -> {
            Collection<TopicPartition> assigned = container.getAssignedPartitions();
            return assigned != null && !assigned.isEmpty();
        });
    }

    private ConcurrentMessageListenerContainer<String, JsonNode> secondConsumer(String groupId) {
        ConcurrentMessageListenerContainer<String, JsonNode> container =
                containerFactory.createContainer(Topics.BUILD_DISPATCHED);
        container.getContainerProperties().setGroupId(groupId);
        container.getContainerProperties().setMessageListener((MessageListener<String, JsonNode>) listener::onMessage);
        container.setBeanName("second-dispatch-consumer");
        return container;
    }
}
