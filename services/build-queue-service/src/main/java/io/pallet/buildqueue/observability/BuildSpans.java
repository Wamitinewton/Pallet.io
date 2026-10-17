package io.pallet.buildqueue.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Continues the trace a build was requested in, from the {@code traceparent} intake stored on it, for work done long
 * after that request ended: a dispatch, a redispatch, an expiry. Work done in scope of {@link #inTraceOf} appends outbox
 * rows that carry the trace on, so build-service's spans join it too.
 */
@Component
public class BuildSpans {

    public static final String DISPATCH = "build_queue.dispatch";
    public static final String REDISPATCH = "build_queue.redispatch";
    public static final String EXPIRE = "build_queue.expire";
    public static final String WAIT = "build_queue.wait";

    public static final String TAG_BUILD_ID = "build_queue.build_id";
    public static final String TAG_ATTEMPT = "build_queue.attempt";

    private static final Pattern TRACEPARENT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    private static final String SAMPLED = "01";

    private final ObjectProvider<Tracer> tracer;

    BuildSpans(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /** Runs {@code work} in a span named {@code name}, a child of the build's stored trace or a new one without it. */
    public <T> T inTraceOf(String name, UUID buildId, String traceparent, Supplier<T> work) {
        Tracer available = tracer.getIfAvailable(() -> Tracer.NOOP);
        Span span = builder(available, name, buildId, traceparent).start();
        try (Tracer.SpanInScope ignored = available.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public void inTraceOf(String name, UUID buildId, String traceparent, Runnable work) {
        inTraceOf(name, buildId, traceparent, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Records the build's wait for its first slot as a span from {@code enqueuedAt} to {@code dispatchedAt}, both read
     * from the row, so a restart in between never shortens it. Reported once the dispatch commits.
     */
    public void recordWait(UUID buildId, String traceparent, Instant enqueuedAt, Instant dispatchedAt) {
        Tracer available = tracer.getIfAvailable(() -> Tracer.NOOP);
        Runnable record = () -> builder(available, WAIT, buildId, traceparent)
                .tag(TAG_ATTEMPT, 1)
                .startTimestamp(micros(enqueuedAt), TimeUnit.MICROSECONDS)
                .start()
                .end(micros(dispatchedAt), TimeUnit.MICROSECONDS);
        AfterCommit.run(record);
    }

    private static Span.Builder builder(Tracer tracer, String name, UUID buildId, String traceparent) {
        Span.Builder builder = tracer.spanBuilder().name(name).tag(TAG_BUILD_ID, buildId.toString());
        TraceContext parent = parse(tracer, traceparent);
        return parent == null ? builder.setNoParent() : builder.setParent(parent);
    }

    /** A malformed value starts a new trace rather than failing the work it would have described. */
    private static TraceContext parse(Tracer tracer, String traceparent) {
        if (traceparent == null || !TRACEPARENT.matcher(traceparent).matches()) {
            return null;
        }
        String[] parts = traceparent.split("-");
        return tracer.traceContextBuilder()
                .traceId(parts[1])
                .spanId(parts[2])
                .sampled(SAMPLED.equals(parts[3]))
                .build();
    }

    private static long micros(Instant instant) {
        return TimeUnit.SECONDS.toMicros(instant.getEpochSecond()) + TimeUnit.NANOSECONDS.toMicros(instant.getNano());
    }
}
