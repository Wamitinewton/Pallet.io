package io.pallet.notification.channel;

/**
 * Signals that a {@link NotificationChannel} failed to deliver. Internal to the channel/router
 * boundary, not an {@code AppException} — never surfaced to an HTTP client.
 */
public class ChannelDeliveryException extends Exception {

    public ChannelDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
