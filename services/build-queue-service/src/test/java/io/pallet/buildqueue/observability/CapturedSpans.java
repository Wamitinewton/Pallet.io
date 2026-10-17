package io.pallet.buildqueue.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Keeps every span this context ends, exported synchronously as it ends, so a test can read a trace back. */
@TestConfiguration(proxyBeanMethods = false)
public class CapturedSpans {

    @Bean
    InMemorySpans inMemorySpans() {
        return new InMemorySpans();
    }

    @Bean
    SpanProcessor capturedSpanProcessor(InMemorySpans spans) {
        return SimpleSpanProcessor.create(spans);
    }

    public static final class InMemorySpans implements SpanExporter {

        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        public List<SpanData> inTrace(String traceId) {
            return spans.stream()
                    .filter(span -> span.getTraceId().equals(traceId))
                    .toList();
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            spans.clear();
            return CompletableResultCode.ofSuccess();
        }
    }
}
