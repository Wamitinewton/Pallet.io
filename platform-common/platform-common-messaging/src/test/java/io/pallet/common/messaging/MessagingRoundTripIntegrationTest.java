package io.pallet.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pallet.common.events.DeployStateChanged;
import io.pallet.common.events.EventHeaders;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.Topics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

@SpringBootTest(
        classes = MessagingRoundTripIntegrationTest.TestApp.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ActiveProfiles("test")
@Testcontainers
class MessagingRoundTripIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false"); // topics must come from our NewTopics beans

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private Listeners listeners;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static String header(ConsumerRecord<String, JsonNode> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void theEnvelopeKeyAndHeadersSurviveTheRoundTrip() throws Exception {
        DeployStateChanged event = DeployStateChanged.of("org_9k2j7f", "dep_rt", "BUILDING", "ROUTING");

        publisher.publish(event);

        ConsumerRecord<String, JsonNode> received = listeners.deployStates.poll(20, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.topic()).isEqualTo(Topics.DEPLOY_STATE_CHANGED);
        assertThat(received.key()).isEqualTo("org_9k2j7f");
        assertThat(received.value().get("deploymentId").asString()).isEqualTo("dep_rt");
        assertThat(received.value().get("fromState").asString()).isEqualTo("BUILDING");
        assertThat(received.value().get("toState").asString()).isEqualTo("ROUTING");
        assertThat(header(received, EventHeaders.EVENT_ID))
                .isEqualTo(event.eventId().toString());
        assertThat(header(received, EventHeaders.EVENT_TYPE)).isEqualTo(Topics.DEPLOY_STATE_CHANGED);
        assertThat(header(received, EventHeaders.ORG_ID)).isEqualTo("org_9k2j7f");
        assertThat(header(received, EventHeaders.TRACEPARENT)).isNotBlank();
    }

    @Test
    void aGuardedListenerHandlesFiveCopiesOfOneEventIdOnce() {
        UUID eventId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            publisher.publish(new NotificationRequested(
                    eventId,
                    NotificationRequested.TYPE,
                    "org_dup",
                    Instant.now(),
                    "TEST",
                    "a@b.c",
                    "EMAIL",
                    null,
                    Map.of("copy", i),
                    null));
        }

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(listeners.notificationReceived).hasValueGreaterThanOrEqualTo(5));
        assertThat(listeners.notificationHandled).hasValue(1);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        Listeners listeners(EventIdempotencyGuard guard) {
            return new Listeners(guard);
        }
    }

    static class Listeners {

        final BlockingQueue<ConsumerRecord<String, JsonNode>> deployStates = new LinkedBlockingQueue<>();
        final AtomicInteger notificationReceived = new AtomicInteger();
        final AtomicInteger notificationHandled = new AtomicInteger();

        private final EventIdempotencyGuard guard;

        Listeners(EventIdempotencyGuard guard) {
            this.guard = guard;
        }

        @KafkaListener(topics = Topics.DEPLOY_STATE_CHANGED, groupId = "round-trip-deploy")
        void onDeployState(ConsumerRecord<String, JsonNode> record) {
            deployStates.add(record);
        }

        @KafkaListener(topics = Topics.NOTIFICATION_REQUESTED, groupId = "round-trip-notification")
        void onNotification(ConsumerRecord<String, JsonNode> record) {
            notificationReceived.incrementAndGet();
            String eventId = record.value().get("eventId").asString();
            if (!guard.markProcessed(eventId, Duration.ofMinutes(5))) {
                return;
            }
            notificationHandled.incrementAndGet();
        }
    }
}
