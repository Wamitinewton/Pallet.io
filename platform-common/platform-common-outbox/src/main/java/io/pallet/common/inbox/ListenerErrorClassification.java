package io.pallet.common.inbox;

import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

/** Marks {@link NonRetryableEventException} as not retryable on the messaging module's listener error handler. */
public final class ListenerErrorClassification {

    public ListenerErrorClassification(CommonErrorHandler errorHandler) {
        if (!(errorHandler instanceof DefaultErrorHandler defaultErrorHandler)) {
            throw new IllegalStateException(
                    "Cannot mark events as non-retryable: listener error handler is " + errorHandler.getClass());
        }
        defaultErrorHandler.addNotRetryableExceptions(NonRetryableEventException.class);
    }
}
