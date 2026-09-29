package io.pallet.gitintegration.delivery;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A handler whose behaviour each test scripts in place of the real lifecycle handler, a backoff that always draws the
 * top of its range so delays are predictable, and every finished span.
 */
@TestConfiguration(proxyBeanMethods = false)
class DeliveryTestConfiguration {

    private static final String LIFECYCLE_HANDLER = "installationLifecycleProcessor";

    /** The processor refuses two handlers for one event, so the scripted handler takes the lifecycle handler's place. */
    @Bean
    static BeanDefinitionRegistryPostProcessor withoutLifecycleHandler() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                registry.removeBeanDefinition(LIFECYCLE_HANDLER);
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}
        };
    }

    @Bean
    ScriptedHandler scriptedHandler() {
        return new ScriptedHandler();
    }

    @Bean
    @Primary
    DeliveryBackoff highestDrawBackoff(GitIntegrationProperties properties) {
        return new DeliveryBackoff(
                properties.delivery().backoffBase(), properties.delivery().backoffMax(), () -> -1L);
    }

    @Bean
    CapturedSpans capturedSpans() {
        return new CapturedSpans();
    }

    /**
     * Handles {@code repository}; {@code installation} and {@code installation_repositories} stay unhandled, and
     * {@code push} and {@code github_app_authorization} have their real handlers.
     */
    static final class ScriptedHandler implements DeliveryHandler {

        private final Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();
        private volatile Function<DeliveryContext, DeliveryOutcome> script = context -> DeliveryOutcome.PROCESSED;

        @Override
        public Set<String> events() {
            return Set.of(SubscribedEvents.REPOSITORY);
        }

        @Override
        public DeliveryOutcome handle(DeliveryContext context) {
            calls.computeIfAbsent(context.deliveryId(), id -> new AtomicInteger())
                    .incrementAndGet();
            return script.apply(context);
        }

        void script(Function<DeliveryContext, DeliveryOutcome> script) {
            this.script = script;
        }

        int calls(UUID deliveryId) {
            AtomicInteger count = calls.get(deliveryId);
            return count == null ? 0 : count.get();
        }

        void reset() {
            calls.clear();
            script = context -> DeliveryOutcome.PROCESSED;
        }
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
