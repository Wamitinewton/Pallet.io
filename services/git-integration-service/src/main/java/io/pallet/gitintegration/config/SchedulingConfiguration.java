package io.pallet.gitintegration.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Boot backs off its own scheduler whenever any {@code ScheduledExecutorService} bean exists, and
 * {@code platform-common-resilience} defines one for its time limiter. Without this bean every {@code @Scheduled} job
 * would run on that pool, beside the GitHub calls it guards, and ignore {@code spring.task.scheduling.*}, including
 * the shutdown wait that lets the delivery processor finish its batch.
 */
@Configuration(proxyBeanMethods = false)
class SchedulingConfiguration {

    @Bean
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }
}
