package io.pallet.common.observability;

import io.pallet.common.events.EventHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationConsumerInterceptorTest {

    private final CorrelationConsumerInterceptor interceptor = new CorrelationConsumerInterceptor(List.of());
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void usesHeaderWhenPresent() {
        ConsumerRecord<Object, Object> record = record(json.createObjectNode());
        record.headers().add(EventHeaders.CORRELATION_ID, "corr-header".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, null);

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("corr-header");
    }

    @Test
    void fallsBackToJsonFieldWhenHeaderMissing() {
        ObjectNode value = json.createObjectNode();
        value.put("correlationId", "corr-payload");

        interceptor.intercept(record(value), null);

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("corr-payload");
    }

    @Test
    void generatesWhenNeitherPresent() {
        interceptor.intercept(record(json.createObjectNode()), null);

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNotBlank();
    }

    @Test
    void readsTraceIdFromTraceparent() {
        ConsumerRecord<Object, Object> record = record(json.createObjectNode());
        record.headers().add(EventHeaders.TRACEPARENT,
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, null);

        assertThat(MDC.get("traceId")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void afterRecordClearsMdc() {
        MDC.put(CorrelationId.MDC_KEY, "corr");
        MDC.put("traceId", "trace");

        interceptor.afterRecord(record(json.createObjectNode()), null);

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    private ConsumerRecord<Object, Object> record(Object value) {
        return new ConsumerRecord<>("pallet.reference.v1", 0, 0L, "key", value);
    }
}
