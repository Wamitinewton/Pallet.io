package io.pallet.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Engages each piece by classpath presence — the aspect for any service, the correlation filter
 * for a servlet web service, the consumer interceptor for a Kafka consumer — and lets a service
 * override any bean or switch a piece off by property.
 */
@AutoConfiguration(
        afterName = "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration")
@EnableConfigurationProperties(ObservabilityProperties.class)
public class PalletObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnClass(Aspect.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pallet.observability", name = "method-metrics-enabled", matchIfMissing = true)
    MonitoringAspect palletMonitoringAspect(MeterRegistry registry) {
        return new MonitoringAspect(registry);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OncePerRequestFilter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "pallet.observability", name = "correlation-filter-enabled", matchIfMissing = true)
    static class CorrelationFilterConfiguration {

        /**
         * {@code SecurityProperties.DEFAULT_FILTER_ORDER + 10}: just after Spring Security's
         * chain, so an {@link MdcContributor} sourced from the authenticated principal has one.
         */
        private static final int FILTER_ORDER = -90;

        @Bean
        @ConditionalOnMissingBean
        CorrelationIdFilter palletCorrelationIdFilter(
                ObservabilityProperties properties, List<MdcContributor> contributors) {
            return new CorrelationIdFilter(properties.correlationHeader(), contributors);
        }

        @Bean
        @ConditionalOnMissingBean(name = "palletCorrelationIdFilterRegistration")
        FilterRegistrationBean<CorrelationIdFilter> palletCorrelationIdFilterRegistration(CorrelationIdFilter filter) {
            FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(FILTER_ORDER);
            return registration;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RecordInterceptor.class, ConsumerRecord.class})
    static class ConsumerInterceptorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CorrelationConsumerInterceptor palletCorrelationConsumerInterceptor(List<MdcContributor> contributors) {
            return new CorrelationConsumerInterceptor(contributors);
        }
    }
}
