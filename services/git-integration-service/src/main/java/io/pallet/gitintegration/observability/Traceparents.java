package io.pallet.gitintegration.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.regex.Pattern;

/**
 * W3C {@code traceparent} values stored beside work that is picked up later, so the span that does the work continues
 * the trace that asked for it.
 */
public final class Traceparents {

    private static final Pattern TRACEPARENT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    private static final String SAMPLED = "01";
    private static final String NOT_SAMPLED = "00";

    private Traceparents() {}

    /** @return the current span's {@code traceparent}, or null when there is none */
    public static String current(Tracer tracer) {
        Span span = tracer == null ? null : tracer.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext context = span.context();
        return String.join(
                "-",
                "00",
                context.traceId(),
                context.spanId(),
                Boolean.TRUE.equals(context.sampled()) ? SAMPLED : NOT_SAMPLED);
    }

    /** Starts a span named {@code name}, a child of {@code traceparent} when it is well formed, else a new trace. */
    public static Span startChild(Tracer tracer, String name, String traceparent) {
        Span.Builder builder = tracer.spanBuilder().name(name);
        if (traceparent != null && TRACEPARENT.matcher(traceparent).matches()) {
            String[] parts = traceparent.split("-");
            builder.setParent(tracer.traceContextBuilder()
                    .traceId(parts[1])
                    .spanId(parts[2])
                    .sampled(SAMPLED.equals(parts[3]))
                    .build());
        }
        return builder.start();
    }
}
