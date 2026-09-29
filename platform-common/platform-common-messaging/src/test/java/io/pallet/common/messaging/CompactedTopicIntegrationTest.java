package io.pallet.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.events.Topics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        classes = CompactedTopicIntegrationTest.TestApp.class,
        properties = {
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "pallet.messaging.compaction.min-lag=2h"
        })
@ActiveProfiles("test")
@Testcontainers
class CompactedTopicIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Autowired
    private PlatformEventPublisher publisher;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static Map<String, Config> describe(String... topics) throws Exception {
        try (AdminClient admin =
                AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            List<ConfigResource> resources = Arrays.stream(topics)
                    .map(topic -> new ConfigResource(ConfigResource.Type.TOPIC, topic))
                    .toList();
            Map<ConfigResource, Config> configs =
                    admin.describeConfigs(resources).all().get();
            Map<String, Config> byTopic = new HashMap<>();
            configs.forEach((resource, config) -> byTopic.put(resource.name(), config));
            return byTopic;
        }
    }

    private static String value(Config config, String name) {
        return config.get(name).value();
    }

    private static List<ConsumerRecord<String, String>> consumeFromStart(String topic, int expected) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "compacted-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(20);
            while (received.size() < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach(received::add);
            }
        }
        return received;
    }

    @Test
    void onlyTheMembershipStateTopicIsCreatedCompacted() throws Exception {
        String dlt = Topics.deadLetter(Topics.ORG_MEMBERSHIP_CHANGED);

        Map<String, Config> configs = describe(Topics.ORG_MEMBERSHIP_CHANGED, dlt, Topics.GIT_PUSH_RECEIVED);

        Config membership = configs.get(Topics.ORG_MEMBERSHIP_CHANGED);
        assertThat(value(membership, TopicConfig.CLEANUP_POLICY_CONFIG)).isEqualTo(TopicConfig.CLEANUP_POLICY_COMPACT);
        assertThat(value(membership, TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG))
                .isEqualTo(String.valueOf(Duration.ofHours(2).toMillis()));
        assertThat(value(configs.get(dlt), TopicConfig.CLEANUP_POLICY_CONFIG))
                .isEqualTo(TopicConfig.CLEANUP_POLICY_DELETE);
        assertThat(value(configs.get(Topics.GIT_PUSH_RECEIVED), TopicConfig.CLEANUP_POLICY_CONFIG))
                .isEqualTo(TopicConfig.CLEANUP_POLICY_DELETE);
    }

    @Test
    void membershipRecordsAndATombstoneRoundTripWithTheirKeys() {
        String first = OrgMembershipChanged.key("org_cmp", "usr_1");
        String second = OrgMembershipChanged.key("org_cmp", "usr_2");

        publisher.publish(
                OrgMembershipChanged.of("org_cmp", "usr_1", "developer", OrgMembershipChanged.STATUS_ACTIVE, 1), first);
        publisher.publish(
                OrgMembershipChanged.of("org_cmp", "usr_1", "admin", OrgMembershipChanged.STATUS_ACTIVE, 2), first);
        publisher.publish(
                OrgMembershipChanged.of("org_cmp", "usr_2", "viewer", OrgMembershipChanged.STATUS_ACTIVE, 1), second);
        publisher.publishTombstone(Topics.ORG_MEMBERSHIP_CHANGED, first);

        List<ConsumerRecord<String, String>> received = consumeFromStart(Topics.ORG_MEMBERSHIP_CHANGED, 4);

        assertThat(received)
                .extracting(ConsumerRecord::key, record -> record.value() == null)
                .containsExactly(
                        tuple("org_cmp:usr_1", false),
                        tuple("org_cmp:usr_1", false),
                        tuple("org_cmp:usr_2", false),
                        tuple("org_cmp:usr_1", true));
        assertThat(received.get(1).value()).contains("\"role\":\"admin\"").contains("\"membershipVersion\":2");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}
}
