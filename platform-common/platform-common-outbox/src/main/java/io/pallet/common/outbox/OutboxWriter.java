package io.pallet.common.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.events.Topics;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends events to the outbox inside the caller's transaction, so a state change and its events commit or roll back
 * together. Every method refuses to run without an active transaction.
 */
public class OutboxWriter {

    private static final String TRACEPARENT_VERSION = "00";
    private static final String SAMPLED = "01";
    private static final String NOT_SAMPLED = "00";

    private final OutboxRepository repository;
    private final OutboxEventTypes eventTypes;
    private final JsonMapper jsonMapper;
    private final ObjectProvider<Tracer> tracer;

    OutboxWriter(
            OutboxRepository repository,
            OutboxEventTypes eventTypes,
            JsonMapper jsonMapper,
            ObjectProvider<Tracer> tracer) {
        this.repository = repository;
        this.eventTypes = eventTypes;
        this.jsonMapper = jsonMapper;
        this.tracer = tracer;
    }

    /** @throws UnknownEventTypeException if the service did not declare the event's type in {@link OutboxEventTypes} */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(PlatformEvent event) {
        append(event, false);
    }

    /** {@code sensitive} payloads are nulled in Postgres once published. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(PlatformEvent event, boolean sensitive) {
        insert(event, sensitive, null);
    }

    /** Publishes under {@code recordKey} instead of {@code orgId}; ordering stays per org. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(PlatformEvent event, String recordKey) {
        insert(event, false, requireKey(recordKey));
    }

    /**
     * Appends a null-value record for {@code recordKey} on a compacted topic. Consumers never see an event id for it.
     *
     * @throws IllegalArgumentException if {@code topic} is not {@link Topics#isCompacted compacted} or the key is blank
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendTombstone(String orgId, String topic, String recordKey) {
        requireKey(recordKey);
        if (!Topics.isCompacted(topic)) {
            throw new IllegalArgumentException("Tombstones are only valid on a compacted topic: " + topic);
        }
        repository.insert(OutboxRow.pendingTombstone(UUID.randomUUID(), orgId, topic, currentTraceparent(), recordKey));
    }

    private void insert(PlatformEvent event, boolean sensitive, String recordKey) {
        eventTypes.classFor(event.eventType());
        repository.insert(OutboxRow.pending(
                event.eventId(),
                event.orgId(),
                event.eventType(),
                jsonMapper.writeValueAsString(event),
                sensitive,
                currentTraceparent(),
                recordKey));
    }

    private static String requireKey(String recordKey) {
        if (recordKey == null || recordKey.isBlank()) {
            throw new IllegalArgumentException("An explicit record key must not be blank");
        }
        return recordKey;
    }

    private String currentTraceparent() {
        Tracer available = tracer.getIfAvailable();
        Span span = available == null ? null : available.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext context = span.context();
        return String.join(
                "-",
                TRACEPARENT_VERSION,
                context.traceId(),
                context.spanId(),
                Boolean.TRUE.equals(context.sampled()) ? SAMPLED : NOT_SAMPLED);
    }
}
