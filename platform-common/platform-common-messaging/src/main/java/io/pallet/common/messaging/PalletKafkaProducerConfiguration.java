package io.pallet.common.messaging;

import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON, idempotent producer. String keys, flat-JSON values (no {@code __TypeId__}
 * header — payloads are plain JSON per ADR-0006), {@code enable.idempotence=true},
 * {@code acks=all}, Micrometer observation on so {@code traceparent} rides along.
 */
@Configuration(proxyBeanMethods = false)
class PalletKafkaProducerConfiguration {

    /**
     * Producer config with the platform's durability flags forced on, whatever a service configured.
     * {@code kafkaProperties.buildProducerProperties()} has no {@link KafkaConnectionDetails}-aware
     * overload in this Boot version, so a {@code @ServiceConnection} container (Testcontainers)
     * would otherwise be silently ignored in favor of the plain {@code spring.kafka.bootstrap-servers}
     * property — apply the connection details' bootstrap servers on top when one is present.
     */
    static Map<String, Object> idempotentProducerConfig(
            KafkaProperties kafkaProperties, ObjectProvider<KafkaConnectionDetails> connectionDetails) {
        Map<String, Object> config = kafkaProperties.buildProducerProperties();
        applyConnectionDetails(config, connectionDetails);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return config;
    }

    static void applyConnectionDetails(
            Map<String, Object> config, ObjectProvider<KafkaConnectionDetails> connectionDetails) {
        KafkaConnectionDetails details = connectionDetails.getIfAvailable();
        if (details != null) {
            config.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, String.join(",", details.getBootstrapServers()));
        }
    }

    static JacksonJsonSerializer<Object> jsonValueSerializer(ObjectProvider<JsonMapper> jsonMapper) {
        JacksonJsonSerializer<Object> serializer = new JacksonJsonSerializer<>(
                jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()));
        serializer.setAddTypeInfo(false);
        return serializer;
    }

    @Bean
    @ConditionalOnMissingBean(ProducerFactory.class)
    ProducerFactory<String, Object> palletKafkaProducerFactory(
            KafkaProperties kafkaProperties,
            ObjectProvider<JsonMapper> jsonMapper,
            ObjectProvider<KafkaConnectionDetails> connectionDetails) {
        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(idempotentProducerConfig(kafkaProperties, connectionDetails));
        factory.setValueSerializer(jsonValueSerializer(jsonMapper));
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean(KafkaTemplate.class)
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> palletKafkaProducerFactory) {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(palletKafkaProducerFactory);
        template.setObservationEnabled(true);
        return template;
    }
}
