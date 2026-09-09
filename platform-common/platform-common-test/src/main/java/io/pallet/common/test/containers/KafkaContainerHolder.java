package io.pallet.common.test.containers;

import org.testcontainers.kafka.KafkaContainer;

/**
 * One lazily-started, never-explicitly-stopped Kafka container per JVM fork, the same
 * singleton-container pattern as {@link PostgresContainerHolder}. Auto topic creation is
 * disabled so a test's own {@code NewTopics} beans are what create topics, matching production
 * behaviour.
 *
 * <p>Image defaults to {@code apache/kafka:3.9.1}; override with
 * {@code -Dpallet.test.kafka.image=...} (see {@link ContainerImages}) if a service needs to test
 * against a different broker version.
 */
final class KafkaContainerHolder {

    static final KafkaContainer CONTAINER = new KafkaContainer(
                    ContainerImages.resolve("pallet.test.kafka.image", "apache/kafka:3.9.1"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withReuse(true);

    static {
        CONTAINER.start();
    }

    private KafkaContainerHolder() {}
}
