package io.pallet.notification.channel;

import io.pallet.notification.domain.Channel;
import io.pallet.notification.template.TemplateRenderer.RenderedNotification;

public interface NotificationChannel {

    Channel type();

    void deliver(RenderedNotification notification, String recipient) throws ChannelDeliveryException;
}
