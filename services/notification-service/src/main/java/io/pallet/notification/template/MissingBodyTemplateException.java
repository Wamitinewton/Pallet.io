package io.pallet.notification.template;

public class MissingBodyTemplateException extends RuntimeException {

    public MissingBodyTemplateException(String notificationType, String expectedLocation) {
        super("Notification type '%s' declares body template '%s' but no matching HTML resource was found"
                .formatted(notificationType, expectedLocation));
    }
}
