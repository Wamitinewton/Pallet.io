package io.pallet.gitintegration.webhook;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Tracer;
import io.pallet.common.api.ApiResponse;
import io.pallet.common.error.AppException;
import io.pallet.common.error.ErrorResponse;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.delivery.DeliveryStore;
import io.pallet.gitintegration.delivery.DeliveryStore.NewDelivery;
import io.pallet.gitintegration.delivery.SubscribedEvents;
import io.pallet.gitintegration.observability.SpanAttributes;
import io.pallet.gitintegration.observability.Traceparents;
import io.pallet.gitintegration.webhook.WebhookEnvelopeReader.WebhookEnvelope;
import io.pallet.gitintegration.webhook.WebhookExceptions.UnsupportedWebhookContentTypeException;
import io.pallet.gitintegration.webhook.WebhookExceptions.WebhookBodyUnreadableException;
import io.pallet.gitintegration.webhook.WebhookExceptions.WebhookPayloadTooLargeException;
import io.pallet.gitintegration.webhook.WebhookExceptions.WebhookSignatureInvalidException;
import io.pallet.gitintegration.webhook.WebhookExceptions.WebhookSourceNotAllowedException;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * GitHub's webhook endpoint (ARCHITECTURE.md §Webhook ingestion): verify, parse the envelope, store, answer. The body
 * is read here, bounded and exactly once, so no message converter or filter touches the bytes the signature covers.
 */
@RestController
@Tag(name = "Webhooks", description = "GitHub's deliveries to Pallet. Called by GitHub only, never by the dashboard.")
class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final String MDC_INSTALLATION_ID = "installationId";

    private final WebhookSignatureVerifier verifier;
    private final WebhookEnvelopeReader envelopeReader;
    private final DeliveryStore store;
    private final WebhookMetrics metrics;
    private final ObjectProvider<Tracer> tracer;
    private final GitHubIpAllowlist allowlist;
    private final int maxBody;

    WebhookController(
            WebhookSignatureVerifier verifier,
            WebhookEnvelopeReader envelopeReader,
            DeliveryStore store,
            WebhookMetrics metrics,
            ObjectProvider<Tracer> tracer,
            GitHubIpAllowlist allowlist,
            GitIntegrationProperties properties) {
        this.verifier = verifier;
        this.envelopeReader = envelopeReader;
        this.store = store;
        this.metrics = metrics;
        this.tracer = tracer;
        this.allowlist = allowlist;
        this.maxBody = Math.toIntExact(properties.webhook().maxBody().toBytes());
    }

    @PostMapping("/git-integration/webhooks/github")
    @SecurityRequirements
    @Operation(
            summary = "Receive a GitHub webhook delivery",
            description = "Called by GitHub only. Authenticated by the X-Hub-Signature-256 HMAC over the raw body, "
                    + "never by a bearer token, so it takes none. The delivery is stored and acknowledged at once and "
                    + "processed afterwards; a redelivery of a stored GUID changes nothing. The payload is GitHub's own "
                    + "event, documented by GitHub.",
            externalDocs =
                    @ExternalDocumentation(
                            description = "GitHub webhook events and payloads",
                            url = "https://docs.github.com/en/webhooks/webhook-events-and-payloads"),
            parameters = {
                @Parameter(
                        name = WebhookHeaders.SIGNATURE,
                        in = ParameterIn.HEADER,
                        required = true,
                        description = "HMAC-SHA256 of the raw body under the app's webhook secret, hex encoded.",
                        schema = @Schema(type = "string", pattern = "^sha256=[0-9a-fA-F]{64}$")),
                @Parameter(
                        name = WebhookHeaders.EVENT,
                        in = ParameterIn.HEADER,
                        required = true,
                        description = "The event name, such as push or installation.",
                        schema = @Schema(type = "string", pattern = "^[a-z_]{1,64}$", example = "push")),
                @Parameter(
                        name = WebhookHeaders.DELIVERY,
                        in = ParameterIn.HEADER,
                        required = true,
                        description =
                                "The delivery GUID; a delivery already stored is answered 200 and not reprocessed.",
                        schema =
                                @Schema(
                                        type = "string",
                                        format = "uuid",
                                        example = "00000000-0000-4000-8000-000000000001"))
            },
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "GitHub's event payload, exactly as GitHub signed it.",
                            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))),
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "202",
                        description = "Stored for processing, or stored and ignored for an event Pallet doesn't use"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "200",
                        description = "A duplicate of a delivery already stored, or a ping"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "400",
                        description = "INVALID_WEBHOOK_HEADERS, MALFORMED_WEBHOOK_PAYLOAD or WEBHOOK_BODY_UNREADABLE, "
                                + "all only after the signature passed",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "401",
                        description = "WEBHOOK_SIGNATURE_INVALID: the signature is missing or wrong",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = "WEBHOOK_SOURCE_NOT_ALLOWED: outside GitHub's hook ranges, only when the IP "
                                + "allowlist is on",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "413",
                        description = "PAYLOAD_TOO_LARGE: the body is over the size limit",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "415",
                        description = "UNSUPPORTED_MEDIA_TYPE: a signed body that isn't application/json",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "503",
                        description = "SERVICE_UNAVAILABLE: the database is unreachable; GitHub retries",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
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
            MDC.remove(MDC_INSTALLATION_ID);
        }
    }

    private Outcome handle(HttpServletRequest request) {
        requireAllowedSource(request.getRemoteAddr());
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
        describeSpan(request, headers, envelope.installationId());
        if (envelope.installationId() != null) {
            MDC.put(MDC_INSTALLATION_ID, envelope.installationId().toString());
        }
        if (envelope.sanitized()) {
            metrics.payloadSanitized();
        }
        boolean stored = store.store(new NewDelivery(
                headers.deliveryId(),
                headers.event(),
                envelope.action(),
                envelope.installationId(),
                envelope.payload(),
                Traceparents.current(tracer.getIfAvailable())));
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

    private void requireAllowedSource(String address) {
        if (!allowlist.enabled()) {
            return;
        }
        switch (allowlist.check(address)) {
            case ALLOWED -> metrics.source(WebhookMetrics.SOURCE_ALLOWED);
            case UNCHECKED -> metrics.source(WebhookMetrics.SOURCE_UNCHECKED);
            case REJECTED -> {
                metrics.source(WebhookMetrics.SOURCE_REJECTED);
                throw new WebhookSourceNotAllowedException();
            }
        }
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

    /** High-cardinality, so the ids reach the server span and never the request metrics. */
    private static void describeSpan(HttpServletRequest request, WebhookHeaders headers, Long installationId) {
        ServerHttpObservationFilter.findObservationContext(request).ifPresent(observation -> {
            observation.addHighCardinalityKeyValue(
                    KeyValue.of(SpanAttributes.EVENT, SubscribedEvents.metricLabel(headers.event())));
            observation.addHighCardinalityKeyValue(
                    KeyValue.of(SpanAttributes.DELIVERY_ID, headers.deliveryId().toString()));
            if (installationId != null) {
                observation.addHighCardinalityKeyValue(
                        KeyValue.of(SpanAttributes.INSTALLATION_ID, installationId.toString()));
            }
        });
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
