package io.pallet.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.inbox.ListenerErrorClassification;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.messaging.MessagingProperties;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.UnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@UnitTest
class PalletOutboxAutoConfigurationTest {

    private static final String[] REQUIRED = {
        "pallet.outbox.schema=svc", "pallet.outbox.metrics-prefix=svc", "pallet.outbox.advisory-lock-key=42"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(ValidationAutoConfiguration.class, PalletOutboxAutoConfiguration.class))
            .withUserConfiguration(Messaging.class);

    @Test
    void wiresTheOutboxTheInboxAndBothHealthIndicatorsForAServiceWithADatabase() {
        runner.withUserConfiguration(Database.class)
                .withPropertyValues(REQUIRED)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(OutboxWriter.class)
                            .hasSingleBean(OutboxRelay.class)
                            .hasSingleBean(OutboxRepository.class)
                            .hasSingleBean(OutboxMetrics.class)
                            .hasSingleBean(TransactionalInbox.class)
                            .hasSingleBean(ListenerErrorClassification.class);
                    assertThat(context.getBean("outbox")).isInstanceOf(HealthIndicator.class);
                    assertThat(context.getBean("kafkaProducer")).isInstanceOf(HealthIndicator.class);
                    assertThat(context.getBean(OutboxEventTypes.class).publishedTypes())
                            .isEmpty();
                });
    }

    @Test
    void aServiceWithoutADatabaseGetsNothing() {
        runner.withPropertyValues(REQUIRED).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .doesNotHaveBean(OutboxWriter.class)
                    .doesNotHaveBean(OutboxRelay.class)
                    .doesNotHaveBean(TransactionalInbox.class);
        });
    }

    @Test
    void theRelayCanBeSwitchedOffWhileTheWriterStays() {
        runner.withUserConfiguration(Database.class)
                .withPropertyValues(REQUIRED)
                .withPropertyValues("pallet.outbox.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OutboxWriter.class).doesNotHaveBean(OutboxRelay.class);
                });
    }

    @Test
    void aMissingAdvisoryLockKeyFailsStartup() {
        runner.withUserConfiguration(Database.class)
                .withPropertyValues("pallet.outbox.schema=svc", "pallet.outbox.metrics-prefix=svc")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().hasStackTraceContaining("advisoryLockKey"));
    }

    @Test
    void theServiceDeclaresWhatItPublishes() {
        runner.withUserConfiguration(Database.class, DeclaredTypes.class)
                .withPropertyValues(REQUIRED)
                .run(context -> assertThat(
                                context.getBean(OutboxEventTypes.class).publishedTypes())
                        .containsExactly(OrgMemberAdded.class));
    }

    @Test
    void aServiceWithTwoClocksStillGetsARelay() {
        runner.withUserConfiguration(Database.class, TwoClocks.class)
                .withPropertyValues(REQUIRED)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OutboxRelay.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Messaging {

        @Bean
        PlatformEventPublisher platformEventPublisher() {
            return mock(PlatformEventPublisher.class);
        }

        @Bean
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate() {
            return mock(KafkaTemplate.class);
        }

        @Bean
        MessagingProperties messagingProperties() {
            return new MessagingProperties(
                    3,
                    new MessagingProperties.Retry(4, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30)),
                    new MessagingProperties.Publish(Duration.ofSeconds(1)),
                    true,
                    (short) 1,
                    3,
                    new MessagingProperties.DltMonitor(true),
                    new MessagingProperties.Compaction(Duration.ofHours(1)));
        }

        @Bean
        CommonErrorHandler commonErrorHandler() {
            return new DefaultErrorHandler();
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Database {

        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DeclaredTypes {

        @Bean
        OutboxEventTypes outboxEventTypes() {
            return OutboxEventTypes.of(OrgMemberAdded.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoClocks {

        @Bean
        Clock systemClock() {
            return Clock.systemUTC();
        }

        @Bean
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
