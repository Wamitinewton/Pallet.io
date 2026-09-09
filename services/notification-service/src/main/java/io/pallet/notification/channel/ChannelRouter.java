package io.pallet.notification.channel;

import io.pallet.notification.audience.Recipient;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.domain.NotificationDelivery;
import io.pallet.notification.repository.NotificationDeliveryRepository;
import io.pallet.notification.template.TemplateRenderer.RenderedNotification;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Fans one rendered {@link Notification} out to every resolved recipient on every selected
 * channel, writing exactly one {@link NotificationDelivery} row per
 * (notification, channel, recipient) triple. The sole writer of {@code notification_deliveries}
 * so the idempotent insert below is the only place that decision is made.
 */
@Component
public class ChannelRouter {

    private final Map<Channel, NotificationChannel> channelsByType;
    private final NotificationDeliveryRepository deliveryRepository;

    ChannelRouter(List<NotificationChannel> channels, NotificationDeliveryRepository deliveryRepository) {
        this.channelsByType =
                channels.stream().collect(Collectors.toMap(NotificationChannel::type, Function.identity()));
        this.deliveryRepository = deliveryRepository;
    }

    public void fanOut(Notification notification, Set<Channel> channels, List<Recipient> recipients) {
        for (Recipient recipient : recipients) {
            for (Channel channel : channels) {
                String address = channel == Channel.EMAIL ? recipient.email() : recipient.userId();
                deliverOne(notification, channel, address);
            }
        }
    }

    private void deliverOne(Notification notification, Channel channel, String recipient) {
        UUID deliveryId = UUID.randomUUID();
        int inserted =
                deliveryRepository.insertPendingIfAbsent(deliveryId, notification.getId(), channel.name(), recipient);
        if (inserted == 0) {
            return;
        }

        RenderedNotification rendered =
                new RenderedNotification(notification.getRenderedTitle(), notification.getRenderedBody());
        NotificationDelivery delivery = deliveryRepository.findById(deliveryId).orElseThrow();
        try {
            channelsByType.get(channel).deliver(rendered, recipient);
            delivery.markSent();
        } catch (ChannelDeliveryException e) {
            delivery.markFailed(e.getMessage());
        }
        deliveryRepository.save(delivery);
    }
}
