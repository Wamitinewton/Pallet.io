package io.pallet.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pallet.common.events.DeployStateChanged;
import io.pallet.common.events.Topics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
        classes = DeadLetterRoutingIntegrationTest.TestApp.class,
        properties = {
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            // The catch-all monitor would re-dead-letter the poison pill it can't parse; not wanted here.
            "pallet.messaging.dlt-monitor.enabled=false"
        })
@ActiveProfiles("test")
@Testcontainers
class DeadLetterRoutingIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private Listeners listeners;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Test
    void aRecordThatKeepsFailingIsRetriedPerPolicyThenDeadLettered() {
        publisher.publish(Topics.BUILD_FAILED, DeployStateChanged.of("org_dlt", "dep_x", "A", "B"));

        ConsumerRecord<String, String> dead = awaitOne(Topics.deadLetter(Topics.BUILD_FAILED));
        assertThat(dead.value()).contains("\"orgId\":\"org_dlt\"");
        assertThat(listeners.buildFailedDeliveries).hasValue(3); // initial + 2 retries (application-test.yml), then DLT
    }

    @Test
    void aNonDeserializableRecordIsDeadLetteredWithoutRetry() throws Exception {
        try (KafkaProducer<String, String> raw = new KafkaProducer<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(),
                new StringSerializer())) {
            raw.send(new ProducerRecord<>(Topics.HEALTH_CHECK_FAILED, "k", "definitely-not-json{"))
                    .get();
        }

        ConsumerRecord<String, String> dead = awaitOne(Topics.deadLetter(Topics.HEALTH_CHECK_FAILED));
        assertThat(dead.value()).isEqualTo("definitely-not-json{");
        assertThat(listeners.healthCheckDeliveries).hasValue(0); // deserialization fails before the listener runs
    }

    private ConsumerRecord<String, String> awaitOne(String topic) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        KAFKA.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "assert-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest"),
                new StringDeserializer(),
                new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            var found = new java.util.concurrent.atomic.AtomicReference<ConsumerRecord<String, String>>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : polled) {
                    found.set(record);
                    return true;
                }
                return false;
            });
            return found.get();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        Listeners listeners() {
            return new Listeners();
        }
    }

    static class Listeners {

        final AtomicInteger buildFailedDeliveries = new AtomicInteger();
        final AtomicInteger healthCheckDeliveries = new AtomicInteger();

        @KafkaListener(topics = Topics.BUILD_FAILED, groupId = "dlt-routing-build")
        void onBuildFailed(ConsumerRecord<String, JsonNode> record) {
            buildFailedDeliveries.incrementAndGet();
            throw new IllegalStateException("always fails");
        }

        @KafkaListener(topics = Topics.HEALTH_CHECK_FAILED, groupId = "dlt-routing-health")
        void onHealthCheckFailed(ConsumerRecord<String, JsonNode> record) {
            healthCheckDeliveries.incrementAndGet();
            throw new IllegalStateException("always fails");
        }
    }
}
