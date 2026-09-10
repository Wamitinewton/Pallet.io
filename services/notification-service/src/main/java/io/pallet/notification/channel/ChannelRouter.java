package io.pallet.notification.channel;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pallet.notification.audience.Recipient;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.domain.NotificationDelivery;
import io.pallet.notification.ratelimit.OrgRateLimiter;
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

    private static final String SENT_METRIC = "notifications.sent";
    private static final String FAILED_METRIC = "notifications.failed";
    private static final String SEND_LATENCY_METRIC = "notifications.send.latency";
    private static final String CHANNEL_TAG = "channel";

    private final Map<Channel, NotificationChannel> channelsByType;
    private final NotificationDeliveryRepository deliveryRepository;
    private final OrgRateLimiter orgRateLimiter;
    private final MeterRegistry meterRegistry;

    public ChannelRouter(
            List<NotificationChannel> channels,
            NotificationDeliveryRepository deliveryRepository,
            OrgRateLimiter orgRateLimiter,
            MeterRegistry meterRegistry) {
        this.channelsByType =
                channels.stream().collect(Collectors.toMap(NotificationChannel::type, Function.identity()));
        this.deliveryRepository = deliveryRepository;
        this.orgRateLimiter = orgRateLimiter;
        this.meterRegistry = meterRegistry;
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

        NotificationDelivery delivery = deliveryRepository.findById(deliveryId).orElseThrow();
        send(notification, delivery);
    }

    /**
     * Delivers one already-persisted row through its channel and records the outcome, gated by a
     * non-blocking per-org permission check. Shared by {@link #fanOut} and by
     * {@code DeliveryRetryScheduler}'s sweep of previously-{@code THROTTLED} rows, so a retried
     * delivery goes through exactly the same rate-limit and channel path as a fresh one.
     */
    public void send(Notification notification, NotificationDelivery delivery) {
        if (!orgRateLimiter.tryAcquire(notification.getOrgId())) {
            delivery.markThrottled();
            deliveryRepository.save(delivery);
            return;
        }

        RenderedNotification rendered =
                new RenderedNotification(notification.getRenderedTitle(), notification.getRenderedBody());
        String channelTag = delivery.getChannel().name();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            channelsByType.get(delivery.getChannel()).deliver(rendered, delivery.getRecipient());
            delivery.markSent();
            meterRegistry.counter(SENT_METRIC, CHANNEL_TAG, channelTag).increment();
        } catch (ChannelDeliveryException e) {
            delivery.markFailed(e.getMessage());
            meterRegistry.counter(FAILED_METRIC, CHANNEL_TAG, channelTag).increment();
        } finally {
            sample.stop(Timer.builder(SEND_LATENCY_METRIC)
                    .tag(CHANNEL_TAG, channelTag)
                    .register(meterRegistry));
        }
        deliveryRepository.save(delivery);
    }
}
