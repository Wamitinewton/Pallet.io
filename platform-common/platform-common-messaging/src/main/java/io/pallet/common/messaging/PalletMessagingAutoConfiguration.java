package io.pallet.common.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Wires the shared messaging stack: the idempotent producer + {@link PlatformEventPublisher},
 * the {@code kafkaListenerContainerFactory}, topic creation, the {@link EventIdempotencyGuard}
 * (Redis-backed when available, in-memory otherwise), and the dead-letter monitor. Every bean
 * is {@code @ConditionalOnMissingBean} so a service overrides any piece.
 */
// Before Boot's Kafka autoconfig so the platform's producer/consumer/container-factory beans are the
// ones its @ConditionalOnMissingBean guards see; a service can still override any of them from its own config.
@AutoConfiguration(before = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(MessagingProperties.class)
@Import({PalletKafkaProducerConfiguration.class, PalletKafkaConsumerConfiguration.class, PalletTopicConfiguration.class
})
public class PalletMessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PalletMessagingAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    PlatformEventPublisher platformEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate, MeterRegistry meterRegistry, MessagingProperties properties) {
        return new DefaultPlatformEventPublisher(kafkaTemplate, meterRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pallet.messaging.dlt-monitor", name = "enabled", matchIfMissing = true)
    DeadLetterTopicListener deadLetterTopicListener(MeterRegistry meterRegistry) {
        return new DeadLetterTopicListener(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(EventIdempotencyGuard.class)
    EventIdempotencyGuard inMemoryEventIdempotencyGuard() {
        log.warn("Using the in-memory EventIdempotencyGuard: it dedupes within a single instance only. "
                + "Add spring-data-redis, or override the bean, before scaling a consumer out.");
        return new InMemoryEventIdempotencyGuard();
    }

    // A nested, classpath-gated configuration: a StringRedisTemplate-typed method parameter directly
    // on PalletMessagingAutoConfiguration would fail Class#getDeclaredMethods() reflection for every
    // condition check on this class once a service omits spring-data-redis entirely (not merely leaves
    // its autoconfiguration disabled) — the type is unresolvable in the class file, not just absent as a
    // bean. Isolating it in its own nested class means that reflection only happens once @ConditionalOnClass
    // has already confirmed the type exists.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    static class RedisIdempotencyGuardConfiguration {

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean(EventIdempotencyGuard.class)
        EventIdempotencyGuard redisEventIdempotencyGuard(StringRedisTemplate redisTemplate) {
            return new RedisEventIdempotencyGuard(redisTemplate);
        }
    }
}
