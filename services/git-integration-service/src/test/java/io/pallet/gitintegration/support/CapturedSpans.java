package io.pallet.gitintegration.support;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Every span the service finishes, as exported. Import {@link Configuration} to register it. */
public final class CapturedSpans implements SpanProcessor {

    private final List<SpanData> finished = new CopyOnWriteArrayList<>();

    public List<SpanData> all() {
        return List.copyOf(finished);
    }

    public List<SpanData> inTrace(String traceId) {
        return finished.stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .toList();
    }

    /** Each span's name, then every attribute as {@code key=value}. */
    public List<String> everyNameAndAttribute() {
        List<String> values = new ArrayList<>();
        for (SpanData span : finished) {
            values.add(span.getName());
            span.getAttributes().forEach((key, value) -> values.add(key.getKey() + "=" + value));
            span.getEvents()
                    .forEach(event ->
                            event.getAttributes().forEach((key, value) -> values.add(key.getKey() + "=" + value)));
        }
        return values;
    }

    public void clear() {
        finished.clear();
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {}

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        finished.add(span.toSpanData());
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        CapturedSpans capturedSpans() {
            return new CapturedSpans();
        }
    }
}
