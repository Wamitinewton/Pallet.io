package io.pallet.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaAdmin;

class PalletMessagingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(KafkaAutoConfiguration.class, PalletMessagingAutoConfiguration.class))
            .withBean(SimpleMeterRegistry.class)
            .withBean(KafkaAdmin.class, () -> mock(KafkaAdmin.class))
            .withPropertyValues(
                    "spring.kafka.bootstrap-servers=localhost:9092", "spring.kafka.listener.auto-startup=false");

    @Test
    void wiresTheProducerTheSharedFactoryTheMonitorAndTheInMemoryGuardByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PlatformEventPublisher.class);
            assertThat(context).hasSingleBean(DeadLetterTopicListener.class);
            assertThat(context).hasBean("kafkaListenerContainerFactory");
            assertThat(context.getBean("kafkaListenerContainerFactory"))
                    .isInstanceOf(ConcurrentKafkaListenerContainerFactory.class);
            assertThat(context.getBean(EventIdempotencyGuard.class)).isInstanceOf(InMemoryEventIdempotencyGuard.class);
        });
    }

    @Test
    void theRedisGuardIsChosenWhenAStringRedisTemplateExists() {
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> assertThat(context.getBean(EventIdempotencyGuard.class))
                        .isInstanceOf(RedisEventIdempotencyGuard.class));
    }

    @Test
    void aServiceSuppliedGuardOverridesTheDefaults() {
        EventIdempotencyGuard custom = mock(EventIdempotencyGuard.class);
        runner.withBean("deliveryLogGuard", EventIdempotencyGuard.class, () -> custom)
                .run(context ->
                        assertThat(context.getBean(EventIdempotencyGuard.class)).isSameAs(custom));
    }

    @Test
    void topicsAndTheirDeadLettersAreDerivedFromTheCatalog() {
        runner.run(context -> {
            assertThat(context).hasBean("palletEventTopics");
            assertThat(context.getBean("palletEventTopics")).isInstanceOf(KafkaAdmin.NewTopics.class);
        });
    }

    @Test
    void topicCreationCanBeTurnedOff() {
        runner.withPropertyValues("pallet.messaging.create-topics=false")
                .run(context -> assertThat(context).doesNotHaveBean(KafkaAdmin.NewTopics.class));
    }

    @Test
    void theMonitorCanBeTurnedOff() {
        runner.withPropertyValues("pallet.messaging.dlt-monitor.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DeadLetterTopicListener.class));
    }
}
