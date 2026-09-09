package io.pallet.notification.template;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;

@Configuration(proxyBeanMethods = false)
class NotificationTemplateConfiguration {

    @Bean
    NotificationTemplateRegistry notificationTemplateRegistry(ResourcePatternResolver resourcePatternResolver) {
        return new NotificationTemplateRegistry(
                resourcePatternResolver, NotificationTemplateRegistry.DEFAULT_LOCATION_PATTERN);
    }
}
