package io.pallet.common.messaging;

import io.pallet.common.events.Topics;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Creates a topic and its {@code .DLT} sibling for every {@link Topics#all()} entry,
 * so the broker can run with auto-create disabled. The list is derived from the
 * catalog — adding a {@code Topics} constant is the only edit.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pallet.messaging", name = "create-topics", matchIfMissing = true)
class PalletTopicConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "palletEventTopics")
    KafkaAdmin.NewTopics palletEventTopics(MessagingProperties properties) {
        int partitions = properties.topicPartitions();
        short replicas = properties.topicReplicas();
        NewTopic[] topics = Topics.all().stream()
            .flatMap(name -> Stream.of(
                new NewTopic(name, partitions, replicas),
                new NewTopic(Topics.deadLetter(name), partitions, replicas)))
            .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topics);
    }
}
