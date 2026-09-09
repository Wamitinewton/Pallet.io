package io.pallet.notification.consumer;

import io.pallet.notification.template.UnknownNotificationTypeException;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * An unknown {@code notificationType} or a malformed {@code audience} string can never succeed on
 * redelivery, so both skip straight to the DLT instead of burning the shared retry/backoff
 * schedule. Mutates the shared {@code palletKafkaErrorHandler} bean rather than replacing it —
 * this service still doesn't own retry/DLT publishing, only which exceptions are retryable.
 */
@Configuration(proxyBeanMethods = false)
class NotificationConsumerConfiguration {

    NotificationConsumerConfiguration(CommonErrorHandler errorHandler) {
        if (errorHandler instanceof DefaultErrorHandler defaultErrorHandler) {
            defaultErrorHandler.addNotRetryableExceptions(
                    UnknownNotificationTypeException.class, IllegalArgumentException.class);
        }
    }
}
