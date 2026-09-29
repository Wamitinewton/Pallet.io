package io.pallet.gitintegration.webhook;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Every tag value here comes from a fixed set, never from the request. */
@Component
public class WebhookMetrics {

    public static final String RECEIVED = "git.webhooks.received";
    public static final String SIGNATURE_FAILURES = "git.webhook.signature_failures";
    public static final String SIGNATURE_VERIFIED = "git.webhook.signature_verified";
    public static final String ACK_LATENCY = "git.webhook.ack_latency";
    public static final String PAYLOAD_SANITIZED = "git.webhook.payload_sanitized";

    public static final String TAG_EVENT = "event";
    public static final String TAG_RESULT = "result";
    public static final String TAG_SECRET = "secret";

    public static final String STORED = "stored";
    public static final String DUPLICATE = "duplicate";
    public static final String IGNORED = "ignored";
    public static final String REJECTED = "rejected";
    public static final String FAILED = "failed";

    private final MeterRegistry registry;
    private final Counter signatureFailures;
    private final Counter payloadSanitized;
    private final Timer ackLatency;

    WebhookMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.signatureFailures = Counter.builder(SIGNATURE_FAILURES)
                .description("Webhook deliveries whose X-Hub-Signature-256 matched no configured secret")
                .register(registry);
        this.payloadSanitized = Counter.builder(PAYLOAD_SANITIZED)
                .description("Stored webhook payloads that had a \\u0000 escape replaced")
                .register(registry);
        this.ackLatency = Timer.builder(ACK_LATENCY)
                .description("Time the webhook handler took to answer GitHub")
                .publishPercentileHistogram()
                .register(registry);
    }

    Timer.Sample start() {
        return Timer.start(registry);
    }

    void handled(Timer.Sample sample, String event, String result) {
        sample.stop(ackLatency);
        registry.counter(RECEIVED, TAG_EVENT, event, TAG_RESULT, result).increment();
    }

    void signatureFailed() {
        signatureFailures.increment();
    }

    void signatureVerified(String secret) {
        registry.counter(SIGNATURE_VERIFIED, TAG_SECRET, secret).increment();
    }

    void payloadSanitized() {
        payloadSanitized.increment();
    }
}
