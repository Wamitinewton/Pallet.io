package io.pallet.common.messaging;

import io.pallet.common.events.Topics;
import io.pallet.common.observability.CorrelationConsumerInterceptor;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared consumer side. Values deserialize to {@link JsonNode} — a topic can
 * carry more than one event type and consumers read fields by name (ADR-0006).
 * {@link ErrorHandlingDeserializer} keeps a poison pill from wedging the
 * partition; {@link DefaultErrorHandler} retries transient failures with
 * exponential backoff and dead-letters to {@link Topics#deadLetter(String)}.
 *
 * <p>The factory is named {@code kafkaListenerContainerFactory} — Spring Kafka's
 * default — so a service's {@code @KafkaListener} picks it up with no
 * {@code containerFactory} attribute.
 */
@Configuration(proxyBeanMethods = false)
class PalletKafkaConsumerConfiguration {

    /**
     * Not a bean: a second {@code KafkaTemplate} bean would trip the primary template's
     * {@code @ConditionalOnMissingBean(KafkaTemplate.class)}. The delegating serializer forwards a
     * deserialization failure's original bytes verbatim and re-serializes a processing failure's JSON.
     */
    private static KafkaTemplate<String, Object> deadLetterTemplate(
            KafkaProperties kafkaProperties,
            ObjectProvider<JsonMapper> jsonMapper,
            ObjectProvider<KafkaConnectionDetails> connectionDetails) {
        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(Object.class, PalletKafkaProducerConfiguration.jsonValueSerializer(jsonMapper));

        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(
                PalletKafkaProducerConfiguration.idempotentProducerConfig(kafkaProperties, connectionDetails),
                new StringSerializer(),
                new DelegatingByTypeSerializer(byType, true));
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(factory);
        template.setObservationEnabled(true);
        return template;
    }

    /**
     * The interceptor is declared {@code RecordInterceptor<Object, Object>}; the factory wants the narrowed type.
     */
    @SuppressWarnings("unchecked")
    private static RecordInterceptor<String, JsonNode> adapt(CorrelationConsumerInterceptor interceptor) {
        return (RecordInterceptor<String, JsonNode>) (RecordInterceptor<?, ?>) interceptor;
    }

    @Bean
    @ConditionalOnMissingBean(name = "palletKafkaConsumerFactory")
    ConsumerFactory<String, JsonNode> palletKafkaConsumerFactory(
            KafkaProperties kafkaProperties,
            ObjectProvider<JsonMapper> jsonMapper,
            ObjectProvider<KafkaConnectionDetails> connectionDetails) {
        JsonMapper mapper = jsonMapper.getIfAvailable(() -> JsonMapper.builder().build());
        ErrorHandlingDeserializer<String> keyDeserializer = new ErrorHandlingDeserializer<>(new StringDeserializer());
        ErrorHandlingDeserializer<JsonNode> valueDeserializer =
                new ErrorHandlingDeserializer<>(new JacksonJsonDeserializer<>(JsonNode.class, mapper, false));
        Map<String, Object> consumerProperties = kafkaProperties.buildConsumerProperties();
        PalletKafkaProducerConfiguration.applyConnectionDetails(consumerProperties, connectionDetails);
        return new DefaultKafkaConsumerFactory<>(consumerProperties, keyDeserializer, valueDeserializer);
    }

    @Bean
    @ConditionalOnMissingBean(name = "palletKafkaErrorHandler")
    CommonErrorHandler palletKafkaErrorHandler(
            KafkaProperties kafkaProperties,
            ObjectProvider<JsonMapper> jsonMapper,
            ObjectProvider<KafkaConnectionDetails> connectionDetails,
            MessagingProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterTemplate(kafkaProperties, jsonMapper, connectionDetails),
                (record, exception) -> new TopicPartition(Topics.deadLetter(record.topic()), -1));

        MessagingProperties.Retry retry = properties.retry();
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        // maxAttempts here counts retries; total deliveries is that plus the first one.
        backOff.setMaxAttempts(Math.max(0, retry.maxAttempts() - 1L));

        // A DeserializationException can never succeed on retry — DefaultErrorHandler already
        // treats it as non-retryable and dead-letters on the first pass. Leave that default.
        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean
    @ConditionalOnMissingBean(name = "kafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, JsonNode> kafkaListenerContainerFactory(
            ConsumerFactory<String, JsonNode> palletKafkaConsumerFactory,
            CommonErrorHandler palletKafkaErrorHandler,
            MessagingProperties properties,
            ObjectProvider<CorrelationConsumerInterceptor> correlationInterceptor) {

        ConcurrentKafkaListenerContainerFactory<String, JsonNode> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(palletKafkaConsumerFactory);
        factory.setConcurrency(properties.consumerConcurrency());
        factory.setCommonErrorHandler(palletKafkaErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);
        correlationInterceptor.ifAvailable(interceptor -> factory.setRecordInterceptor(adapt(interceptor)));
        return factory;
    }
}
