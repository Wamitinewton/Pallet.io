package io.pallet.common.resilience;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Builds the Resilience4j registries from {@link ResilienceProperties}, the shared
 * {@link ExternalCall} helper, and (when a {@link MeterRegistry} is present) binds all three
 * registries to it. Every bean is {@link ConditionalOnMissingBean} so a service can override any
 * piece.
 */
@AutoConfiguration
@ConditionalOnClass(CircuitBreakerRegistry.class)
@EnableConfigurationProperties(ResilienceProperties.class)
public class PalletResilienceAutoConfiguration {

    // Small and fixed by default: this pool executes the guarded supplier while the time limiter enforces the timeout.
    // Services with high concurrency / long-running blocking calls should override the palletResilienceScheduler bean with a suitably sized executor.

    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();

    @Bean
    @ConditionalOnMissingBean
    ResilienceRegistries resilienceRegistries(ResilienceProperties properties) {
        return new ResilienceRegistries(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    CircuitBreakerRegistry circuitBreakerRegistry(ResilienceRegistries registries) {
        return registries.circuitBreakerRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    RetryRegistry retryRegistry(ResilienceRegistries registries) {
        return registries.retryRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    TimeLimiterRegistry timeLimiterRegistry(ResilienceRegistries registries) {
        return registries.timeLimiterRegistry();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "palletResilienceScheduler")
    ScheduledExecutorService palletResilienceScheduler() {
        return Executors.newScheduledThreadPool(SCHEDULER_POOL_SIZE, PalletResilienceAutoConfiguration::newDaemonThread);
    }

    @Bean
    @ConditionalOnMissingBean
    ExternalCall externalCall(ResilienceRegistries registries, ScheduledExecutorService palletResilienceScheduler) {
        return new ExternalCallExecutor(registries, palletResilienceScheduler);
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(name = "resilienceMetricsBinder")
    ApplicationRunner resilienceMetricsBinder(ResilienceRegistries registries, MeterRegistry meterRegistry) {
        return args -> {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registries.circuitBreakerRegistry()).bindTo(meterRegistry);
            TaggedRetryMetrics.ofRetryRegistry(registries.retryRegistry()).bindTo(meterRegistry);
            TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(registries.timeLimiterRegistry()).bindTo(meterRegistry);
        };
    }

    private static Thread newDaemonThread(Runnable task) {
        Thread thread = new Thread(task, "pallet-resilience-" + THREAD_COUNT.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}
