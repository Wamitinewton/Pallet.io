package io.pallet.gitintegration.webhook;

import io.pallet.gitintegration.webhook.WebhookExceptions.InvalidWebhookHeadersException;
import java.util.UUID;
import java.util.regex.Pattern;

/** The GitHub delivery headers, validated. Read only after the signature has passed. */
public record WebhookHeaders(String event, UUID deliveryId) {

    public static final String EVENT = "X-GitHub-Event";
    public static final String DELIVERY = "X-GitHub-Delivery";
    public static final String SIGNATURE = "X-Hub-Signature-256";

    public static final String PING = "ping";

    private static final Pattern EVENT_NAME = Pattern.compile("[a-z_]{1,64}");
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

    /** @throws InvalidWebhookHeadersException naming the first header that is missing or malformed */
    public static WebhookHeaders parse(String event, String deliveryId) {
        if (event == null || !EVENT_NAME.matcher(event).matches()) {
            throw new InvalidWebhookHeadersException(EVENT);
        }
        if (deliveryId == null || !CANONICAL_UUID.matcher(deliveryId).matches()) {
            throw new InvalidWebhookHeadersException(DELIVERY);
        }
        return new WebhookHeaders(event, UUID.fromString(deliveryId));
    }

    public boolean isPing() {
        return PING.equals(event);
    }
}
