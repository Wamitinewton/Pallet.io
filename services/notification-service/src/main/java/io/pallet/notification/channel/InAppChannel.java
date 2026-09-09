package io.pallet.notification.channel;

import io.pallet.notification.domain.Channel;
import io.pallet.notification.template.TemplateRenderer.RenderedNotification;
import org.springframework.stereotype.Component;

/**
 * Delivery for this channel {@code is} persistence: {@link ChannelRouter} writes the
 * {@code SENT} delivery row before calling this method, so there is nothing left to do here.
 */
@Component
class InAppChannel implements NotificationChannel {

    @Override
    public Channel type() {
        return Channel.IN_APP;
    }

    @Override
    public void deliver(RenderedNotification notification, String recipient) {}
}
