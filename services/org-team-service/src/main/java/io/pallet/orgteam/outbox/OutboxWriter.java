package io.pallet.orgteam.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.pallet.common.events.PlatformEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OutboxWriter {

    private static final String TRACEPARENT_VERSION = "00";
    private static final String SAMPLED = "01";
    private static final String NOT_SAMPLED = "00";

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;
    private final ObjectProvider<Tracer> tracer;

    OutboxWriter(OutboxRepository repository, JsonMapper jsonMapper, ObjectProvider<Tracer> tracer) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.tracer = tracer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(PlatformEvent event) {
        append(event, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(PlatformEvent event, boolean sensitive) {
        repository.insert(OutboxEvent.pending(
                event.eventId(),
                event.orgId(),
                event.eventType(),
                jsonMapper.writeValueAsString(event),
                sensitive,
                currentTraceparent()));
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
