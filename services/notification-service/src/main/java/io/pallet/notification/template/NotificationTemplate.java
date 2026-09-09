package io.pallet.notification.template;

import io.pallet.notification.domain.Channel;
import java.util.Set;

public record NotificationTemplate(
        String notificationType, Set<Channel> defaultChannels, String subjectTemplate, String bodyTemplate) {

    public NotificationTemplate {
        defaultChannels = Set.copyOf(defaultChannels);
    }
}
