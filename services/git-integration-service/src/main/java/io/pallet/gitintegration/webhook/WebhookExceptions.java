package io.pallet.gitintegration.webhook;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

/** Rejections of an inbound delivery. None carries anything from the request body into a message or a log line. */
public final class WebhookExceptions {

    private WebhookExceptions() {}

    /** Says nothing about which secret was tried or why the check failed. */
    public static class WebhookSignatureInvalidException extends AppException {

        public WebhookSignatureInvalidException() {
            super(
                    HttpStatus.UNAUTHORIZED,
                    "WEBHOOK_SIGNATURE_INVALID",
                    "The webhook signature is invalid.",
                    "Webhook signature missing or invalid");
        }
    }

    public static class WebhookPayloadTooLargeException extends AppException {

        public WebhookPayloadTooLargeException(long maxBytes) {
            super(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "PAYLOAD_TOO_LARGE",
                    "The webhook payload is too large.",
                    "Webhook body over " + maxBytes + " bytes");
        }
    }

    public static class WebhookBodyUnreadableException extends AppException {

        public WebhookBodyUnreadableException() {
            super(
                    HttpStatus.BAD_REQUEST,
                    "WEBHOOK_BODY_UNREADABLE",
                    "The webhook body could not be read.",
                    "Reading the webhook body failed");
        }
    }

    public static class UnsupportedWebhookContentTypeException extends AppException {

        public UnsupportedWebhookContentTypeException() {
            super(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "Webhook payloads must be sent as application/json.",
                    "Webhook content type is not application/json");
        }
    }

    public static class InvalidWebhookHeadersException extends AppException {

        public InvalidWebhookHeadersException(String header) {
            super(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_WEBHOOK_HEADERS",
                    "The " + header + " header is missing or invalid.",
                    "Invalid " + header + " header");
        }
    }

    /** {@code reason} is a fixed description of the broken rule, never text from the payload. */
    public static class MalformedWebhookPayloadException extends AppException {

        public MalformedWebhookPayloadException(String reason) {
            super(
                    HttpStatus.BAD_REQUEST,
                    "MALFORMED_WEBHOOK_PAYLOAD",
                    "The webhook payload is not valid JSON or breaks a size limit.",
                    "Webhook payload rejected: " + reason);
        }
    }
}
