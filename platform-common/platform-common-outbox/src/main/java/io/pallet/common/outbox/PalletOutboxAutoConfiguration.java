package io.pallet.common.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.pallet.common.inbox.InboxProperties;
import io.pallet.common.inbox.ListenerErrorClassification;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.messaging.MessagingProperties;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the transactional outbox and inbox for a service that owns a Postgres schema. The service declares the event
 * types it publishes as an {@link OutboxEventTypes} bean and creates the tables from {@code reference-schema.sql}.
 */
@AutoConfiguration(
        afterName = {
            "io.pallet.common.messaging.PalletMessagingAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
        })
@ConditionalOnClass({JdbcClient.class, KafkaTemplate.class})
@ConditionalOnBean({DataSource.class, PlatformEventPublisher.class})
@EnableConfigurationProperties({OutboxProperties.class, InboxProperties.class})
public class PalletOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxEventTypes outboxEventTypes() {
        return OutboxEventTypes.none();
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxRepository outboxRepository(DataSource dataSource, OutboxProperties properties) {
        return new OutboxRepository(JdbcClient.create(dataSource), properties);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxWriter outboxWriter(
            OutboxRepository outboxRepository,
            OutboxEventTypes outboxEventTypes,
            JsonMapper jsonMapper,
            ObjectProvider<Tracer> tracer) {
        return new OutboxWriter(outboxRepository, outboxEventTypes, jsonMapper, tracer);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxMetrics outboxMetrics(
            OutboxRepository outboxRepository, MeterRegistry meterRegistry, OutboxProperties properties) {
        return new OutboxMetrics(outboxRepository, meterRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pallet.outbox", name = "enabled", matchIfMissing = true)
    OutboxRelay outboxRelay(
            OutboxRepository outboxRepository,
            OutboxEventTypes outboxEventTypes,
            PlatformEventPublisher publisher,
            JsonMapper jsonMapper,
            PlatformTransactionManager transactionManager,
            OutboxProperties properties,
            ObjectProvider<Clock> clock,
            OutboxMetrics outboxMetrics,
            ObjectProvider<Tracer> tracer) {
        return new OutboxRelay(
                outboxRepository,
                outboxEventTypes,
                publisher,
                jsonMapper,
                transactionManager,
                properties,
                clock.getIfUnique(Clock::systemUTC),
                outboxMetrics,
                tracer);
    }

    @Bean
    @ConditionalOnMissingBean
    TransactionalInbox transactionalInbox(
            DataSource dataSource, MeterRegistry meterRegistry, OutboxProperties properties) {
        return new TransactionalInbox(JdbcClient.create(dataSource), meterRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CommonErrorHandler.class)
    ListenerErrorClassification listenerErrorClassification(CommonErrorHandler errorHandler) {
        return new ListenerErrorClassification(errorHandler);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class OutboxSchedulingConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    static class OutboxHealthConfiguration {

        @Bean(name = "outbox")
        @ConditionalOnMissingBean(name = "outbox")
        HealthIndicator outboxHealthIndicator(OutboxMetrics outboxMetrics) {
            return new OutboxHealthIndicator(outboxMetrics);
        }

        @Bean(name = "kafkaProducer")
        @ConditionalOnMissingBean(name = "kafkaProducer")
        HealthIndicator kafkaProducerHealthIndicator(
                KafkaTemplate<String, Object> kafkaTemplate,
                OutboxEventTypes outboxEventTypes,
                MessagingProperties messagingProperties) {
            return new KafkaProducerHealthIndicator(kafkaTemplate, outboxEventTypes, messagingProperties);
        }
    }
}
