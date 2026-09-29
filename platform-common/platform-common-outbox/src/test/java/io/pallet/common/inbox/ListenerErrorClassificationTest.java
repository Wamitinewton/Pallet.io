package io.pallet.common.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

@UnitTest
class ListenerErrorClassificationTest {

    @Test
    void marksNonRetryableEventsAsNotRetryableOnTheDefaultErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler();

        new ListenerErrorClassification(handler);

        assertThat(handler.removeClassification(NonRetryableEventException.class))
                .as("classified as not retryable")
                .isFalse();
    }

    @Test
    void refusesAHandlerItCannotClassifyOn() {
        CommonErrorHandler custom = new CommonErrorHandler() {};

        assertThatThrownBy(() -> new ListenerErrorClassification(custom))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-retryable");
    }
}
