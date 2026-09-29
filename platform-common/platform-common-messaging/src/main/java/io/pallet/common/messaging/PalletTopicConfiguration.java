package io.pallet.common.messaging;

import io.pallet.common.events.Topics;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Creates a topic and its {@code .DLT} sibling for every {@link Topics#all()} entry,
 * so the broker can run with auto-create disabled. The list is derived from the
 * catalog — adding a {@code Topics} constant is the only edit. A topic
 * {@link Topics#isCompacted} marks is created with {@code cleanup.policy=compact};
 * its {@code .DLT} keeps delete, so no poison record is compacted away.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pallet.messaging", name = "create-topics", matchIfMissing = true)
class PalletTopicConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "palletEventTopics")
    KafkaAdmin.NewTopics palletEventTopics(MessagingProperties properties) {
        int partitions = properties.topicPartitions();
        short replicas = properties.topicReplicas();
        Map<String, String> compacted = Map.of(
                TopicConfig.CLEANUP_POLICY_CONFIG,
                TopicConfig.CLEANUP_POLICY_COMPACT,
                TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG,
                String.valueOf(properties.compaction().minLag().toMillis()));
        NewTopic[] topics = Topics.all().stream()
                .flatMap(name -> {
                    NewTopic topic = new NewTopic(name, partitions, replicas);
                    if (Topics.isCompacted(name)) {
                        topic.configs(compacted);
                    }
                    return Stream.of(topic, new NewTopic(Topics.deadLetter(name), partitions, replicas));
                })
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topics);
    }
}
