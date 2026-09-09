package io.pallet.notification.idempotency;

import io.pallet.common.messaging.EventIdempotencyGuard;
import io.pallet.notification.repository.NotificationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class NotificationIdempotencyConfiguration {

    @Bean
    @ConditionalOnMissingBean(EventIdempotencyGuard.class)
    EventIdempotencyGuard notificationEventIdempotencyGuard(NotificationRepository notificationRepository) {
        return new NotificationEventIdempotencyGuard(notificationRepository);
    }
}
