package io.pallet.orgteam.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class CapturedSpansConfiguration {

    @Bean
    CapturedSpans capturedSpans() {
        return new CapturedSpans();
    }

    static final class CapturedSpans implements SpanProcessor {

        private final List<SpanData> finished = new CopyOnWriteArrayList<>();

        List<SpanData> all() {
            return List.copyOf(finished);
        }

        void clear() {
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
    }
}
