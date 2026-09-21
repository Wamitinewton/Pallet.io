package io.pallet.orgteam.inbox;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

@Configuration(proxyBeanMethods = false)
class ListenerErrorClassification {

    ListenerErrorClassification(CommonErrorHandler errorHandler) {
        if (!(errorHandler instanceof DefaultErrorHandler defaultErrorHandler)) {
            throw new IllegalStateException(
                    "Cannot mark events as non-retryable: listener error handler is " + errorHandler.getClass());
        }
        defaultErrorHandler.addNotRetryableExceptions(NonRetryableEventException.class);
    }
}
