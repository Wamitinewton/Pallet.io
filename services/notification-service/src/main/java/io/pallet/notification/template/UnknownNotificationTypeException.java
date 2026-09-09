package io.pallet.notification.template;

public class UnknownNotificationTypeException extends RuntimeException {

    public UnknownNotificationTypeException(String notificationType) {
        super("No template registered for notification type '%s'".formatted(notificationType));
    }
}
