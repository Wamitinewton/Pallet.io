package io.pallet.common.observability;

import io.pallet.common.events.EventHeaders;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer-side counterpart of {@link CorrelationIdFilter}: pulls the correlation id off the
 * record (header, then a {@code correlationId} field on a JSON value, then generated) and the
 * trace id out of {@code traceparent} into the MDC for the listener's turn, so a consumer's log
 * lines line up with the producer's. Wired onto the container factory in the messaging module.
 */
public class CorrelationConsumerInterceptor implements RecordInterceptor<Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(CorrelationConsumerInterceptor.class);
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final MdcContributors contributors;
    private final ThreadLocal<List<String>> contributed = ThreadLocal.withInitial(ArrayList::new);

    public CorrelationConsumerInterceptor(List<MdcContributor> contributors) {
        this.contributors = new MdcContributors(contributors);
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        MDC.put(CorrelationId.MDC_KEY, resolveCorrelationId(record));
        traceId(record).ifPresent(id -> MDC.put(TRACE_ID_MDC_KEY, id));
        contributed.set(contributors.apply());
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(CorrelationId.MDC_KEY);
        MDC.remove(TRACE_ID_MDC_KEY);
        contributed.get().forEach(MDC::remove);
        contributed.remove();
    }

    private String resolveCorrelationId(ConsumerRecord<Object, Object> record) {
        String fromHeader = header(record, EventHeaders.CORRELATION_ID);
        if (fromHeader != null) {
            return fromHeader;
        }
        if (record.value() instanceof JsonNode value && value.hasNonNull("correlationId")) {
            return value.get("correlationId").asString();
        }
        String generated = UUID.randomUUID().toString();
        log.warn("Record on {}-{}@{} carried no correlation id — generated {}",
                record.topic(), record.partition(), record.offset(), generated);
        return generated;
    }

    /** W3C traceparent is {@code version-traceId-spanId-flags}; the middle field is the trace id. */
    private Optional<String> traceId(ConsumerRecord<Object, Object> record) {
        String traceparent = header(record, EventHeaders.TRACEPARENT);
        if (traceparent == null) {
            return Optional.empty();
        }
        String[] parts = traceparent.split("-");
        return parts.length >= 3 ? Optional.of(parts[1]) : Optional.empty();
    }

    private String header(ConsumerRecord<?, ?> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
