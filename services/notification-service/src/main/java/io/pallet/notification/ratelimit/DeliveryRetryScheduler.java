package io.pallet.notification.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.notification.channel.ChannelRouter;
import io.pallet.notification.config.NotificationServiceProperties;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.domain.NotificationDelivery;
import io.pallet.notification.repository.NotificationDeliveryRepository;
import io.pallet.notification.repository.NotificationRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code THROTTLED} deliveries independently of the Kafka consumer, decoupling ingestion
 * speed from delivery pacing. Never runs a delivery straight to a terminal state without passing
 * back through {@link ChannelRouter#send}, and never lets a batch bigger than
 * {@code sweep-batch-limit} through in one tick.
 */
@Component
class DeliveryRetryScheduler {

    private static final String RETRIED_METRIC = "notifications.retried";
    private static final String CHANNEL_TAG = "channel";

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationRepository notificationRepository;
    private final ChannelRouter channelRouter;
    private final NotificationServiceProperties properties;
    private final MeterRegistry meterRegistry;

    DeliveryRetryScheduler(
            NotificationDeliveryRepository deliveryRepository,
            NotificationRepository notificationRepository,
            ChannelRouter channelRouter,
            NotificationServiceProperties properties,
            MeterRegistry meterRegistry) {
        this.deliveryRepository = deliveryRepository;
        this.notificationRepository = notificationRepository;
        this.channelRouter = channelRouter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${pallet.notification.rate-limit.sweep-interval:30s}")
    void sweep() {
        List<UUID> claimed =
                deliveryRepository.claimThrottled(properties.rateLimit().sweepBatchLimit());
        if (claimed.isEmpty()) {
            return;
        }
        Map<UUID, NotificationDelivery> deliveries = deliveryRepository.findAllById(claimed).stream()
                .collect(Collectors.toMap(NotificationDelivery::getId, Function.identity()));

        for (UUID id : claimed) {
            NotificationDelivery delivery = deliveries.get(id);
            Notification notification = notificationRepository
                    .findById(delivery.getNotificationId())
                    .orElseThrow();
            meterRegistry
                    .counter(RETRIED_METRIC, CHANNEL_TAG, delivery.getChannel().name())
                    .increment();
            channelRouter.send(notification, delivery);
        }
    }
}
