package io.pallet.gitintegration.webhook;

import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.pallet.common.api.ApiResponse;
import io.pallet.common.error.AppException;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.delivery.DeliveryStore;
import io.pallet.gitintegration.delivery.DeliveryStore.NewDelivery;
import io.pallet.gitintegration.delivery.SubscribedEvents;
import io.pallet.gitintegration.webhook.WebhookEnvelopeReader.WebhookEnvelope;
import io.pallet.gitintegration.webhook.WebhookExceptions.UnsupportedWebhookContentTypeException;
import io.pallet.gitintegration.webhook.WebhookExceptions.WebhookBodyUnreadableException;
import io.pallet.gitintegration.webhook.WebhookExceptions.WebhookPayloadTooLargeException;
import io.pallet.gitintegration.webhook.WebhookExceptions.WebhookSignatureInvalidException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub's webhook endpoint (ARCHITECTURE.md §Webhook ingestion): verify, parse the envelope, store, answer. The body
 * is read here, bounded and exactly once, so no message converter or filter touches the bytes the signature covers.
 */
@RestController
class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookSignatureVerifier verifier;
    private final WebhookEnvelopeReader envelopeReader;
    private final DeliveryStore store;
    private final WebhookMetrics metrics;
    private final ObjectProvider<Tracer> tracer;
    private final int maxBody;

    WebhookController(
            WebhookSignatureVerifier verifier,
            WebhookEnvelopeReader envelopeReader,
            DeliveryStore store,
            WebhookMetrics metrics,
            ObjectProvider<Tracer> tracer,
            GitIntegrationProperties properties) {
        this.verifier = verifier;
        this.envelopeReader = envelopeReader;
        this.store = store;
        this.metrics = metrics;
        this.tracer = tracer;
        this.maxBody = Math.toIntExact(properties.webhook().maxBody().toBytes());
    }

    @PostMapping("/git-integration/webhooks/github")
    ResponseEntity<ApiResponse<Void>> receive(HttpServletRequest request) {
        Timer.Sample sample = metrics.start();
        String eventLabel = SubscribedEvents.metricLabel(request.getHeader(WebhookHeaders.EVENT));
        String result = WebhookMetrics.FAILED;
        try {
            Outcome outcome = handle(request);
            result = outcome.result;
            return ResponseEntity.status(outcome.status).body(ApiResponse.ok(outcome.message));
        } catch (AppException rejected) {
            result = WebhookMetrics.REJECTED;
            throw rejected;
        } finally {
            metrics.handled(sample, eventLabel, result);
        }
    }

    private Outcome handle(HttpServletRequest request) {
        byte[] body = readBounded(request);
        Optional<String> secret = verifier.verify(body, request.getHeader(WebhookHeaders.SIGNATURE));
        if (secret.isEmpty()) {
            metrics.signatureFailed();
            throw new WebhookSignatureInvalidException();
        }
        metrics.signatureVerified(secret.get());
        requireJson(request.getContentType());
        WebhookHeaders headers = WebhookHeaders.parse(
                request.getHeader(WebhookHeaders.EVENT), request.getHeader(WebhookHeaders.DELIVERY));
        if (headers.isPing()) {
            return Outcome.PONG;
        }
        WebhookEnvelope envelope = envelopeReader.read(body);
        if (envelope.sanitized()) {
            metrics.payloadSanitized();
        }
        boolean stored = store.store(new NewDelivery(
                headers.deliveryId(),
                headers.event(),
                envelope.action(),
                envelope.installationId(),
                envelope.payload(),
                currentTraceparent()));
        log.debug(
                "Webhook delivery {} event {} installation {} {}",
                headers.deliveryId(),
                headers.event(),
                envelope.installationId(),
                stored ? "stored" : "already stored");
        if (!stored) {
            return Outcome.DUPLICATE;
        }
        return SubscribedEvents.contains(headers.event()) ? Outcome.STORED : Outcome.IGNORED;
    }

    private byte[] readBounded(HttpServletRequest request) {
        if (request.getContentLengthLong() > maxBody) {
            throw new WebhookPayloadTooLargeException(maxBody);
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(maxBody + 1);
        } catch (IOException e) {
            throw new WebhookBodyUnreadableException();
        }
        if (body.length > maxBody) {
            throw new WebhookPayloadTooLargeException(maxBody);
        }
        return body;
    }

    private static void requireJson(String contentType) {
        try {
            if (contentType == null
                    || !MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType))) {
                throw new UnsupportedWebhookContentTypeException();
            }
        } catch (InvalidMediaTypeException e) {
            throw new UnsupportedWebhookContentTypeException();
        }
    }

    /** The W3C {@code traceparent} of this request's server span, so the processor can link its span to it. */
    private String currentTraceparent() {
        Tracer available = tracer.getIfAvailable();
        Span span = available == null ? null : available.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext context = span.context();
        return String.join(
                "-", "00", context.traceId(), context.spanId(), Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    }

    private enum Outcome {
        STORED(HttpStatus.ACCEPTED, "Delivery accepted.", WebhookMetrics.STORED),
        IGNORED(HttpStatus.ACCEPTED, "Delivery accepted.", WebhookMetrics.IGNORED),
        DUPLICATE(HttpStatus.OK, "Delivery already received.", WebhookMetrics.DUPLICATE),
        PONG(HttpStatus.OK, "pong", WebhookMetrics.IGNORED);

        private final HttpStatus status;
        private final String message;
        private final String result;

        Outcome(HttpStatus status, String message, String result) {
            this.status = status;
            this.message = message;
            this.result = result;
        }
    }
}
