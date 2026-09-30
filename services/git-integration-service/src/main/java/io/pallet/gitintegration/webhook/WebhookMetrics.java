package io.pallet.gitintegration.webhook;

import static io.pallet.gitintegration.observability.MetricsCatalog.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Every tag value here comes from a fixed set, never from the request. */
@Component
public class WebhookMetrics {

    public static final String STORED = "stored";
    public static final String DUPLICATE = "duplicate";
    public static final String IGNORED = "ignored";
    public static final String REJECTED = "rejected";
    public static final String FAILED = "failed";

    public static final String SOURCE_ALLOWED = "allowed";
    public static final String SOURCE_REJECTED = "rejected";
    public static final String SOURCE_UNCHECKED = "unchecked";

    private final MeterRegistry registry;
    private final Counter signatureFailures;
    private final Counter payloadSanitized;
    private final Timer ackLatency;

    WebhookMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.signatureFailures = Counter.builder(WEBHOOK_SIGNATURE_FAILURES)
                .description("Webhook deliveries whose X-Hub-Signature-256 matched no configured secret")
                .register(registry);
        this.payloadSanitized = Counter.builder(WEBHOOK_PAYLOAD_SANITIZED)
                .description("Stored webhook payloads that had a \\u0000 escape replaced")
                .register(registry);
        this.ackLatency = Timer.builder(WEBHOOK_ACK_LATENCY)
                .description("Time the webhook handler took to answer GitHub")
                .publishPercentileHistogram()
                .serviceLevelObjectives(ACK_LATENCY_SLO.toArray(Duration[]::new))
                .register(registry);
        for (String result : new String[] {SOURCE_ALLOWED, SOURCE_REJECTED, SOURCE_UNCHECKED}) {
            sourceCounter(result);
        }
    }

    Timer.Sample start() {
        return Timer.start(registry);
    }

    void handled(Timer.Sample sample, String event, String result) {
        sample.stop(ackLatency);
        registry.counter(WEBHOOKS_RECEIVED, TAG_EVENT, event, TAG_RESULT, result)
                .increment();
    }

    void signatureFailed() {
        signatureFailures.increment();
    }

    void signatureVerified(String secret) {
        registry.counter(WEBHOOK_SIGNATURE_VERIFIED, TAG_SECRET, secret).increment();
    }

    void payloadSanitized() {
        payloadSanitized.increment();
    }

    void source(String result) {
        sourceCounter(result).increment();
    }

    private Counter sourceCounter(String result) {
        return Counter.builder(WEBHOOK_IP_ALLOWLIST)
                .description("Webhook source addresses checked against GitHub's hook ranges; unchecked means allowed"
                        + " because the ranges never loaded")
                .tag(TAG_RESULT, result)
                .register(registry);
    }
}
