package io.pallet.common.test.containers;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Hands {@link KafkaContainerHolder}'s already-running singleton to Spring via
 * {@code @ServiceConnection}, which binds {@code spring.kafka.bootstrap-servers} itself. See
 * {@link PostgresTestContainerConfiguration} for why returning the holder's static instance is
 * what keeps the container singleton.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestContainerConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return KafkaContainerHolder.CONTAINER;
    }
}
