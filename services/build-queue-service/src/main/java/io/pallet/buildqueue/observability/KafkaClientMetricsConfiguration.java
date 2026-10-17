package io.pallet.buildqueue.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;

/**
 * Binds the Kafka consumer client's own metrics, consumer lag among them, which {@code platform-common-messaging}'s
 * consumer factory leaves out. Added before any listener container starts, so every consumer it creates is bound.
 */
@Configuration(proxyBeanMethods = false)
class KafkaClientMetricsConfiguration {

    @Bean
    SmartInitializingSingleton kafkaConsumerClientMetrics(
            @Qualifier("palletKafkaConsumerFactory") ConsumerFactory<?, ?> consumerFactory, MeterRegistry registry) {
        return () -> bind(consumerFactory, registry);
    }

    private static <K, V> void bind(ConsumerFactory<K, V> consumerFactory, MeterRegistry registry) {
        consumerFactory.addListener(new MicrometerConsumerListener<>(registry));
    }
}
