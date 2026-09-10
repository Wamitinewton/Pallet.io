package io.pallet.notification.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.domain.NotificationDelivery;
import io.pallet.notification.repository.NotificationDeliveryRepository;
import io.pallet.notification.repository.NotificationRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and mutates {@code IN_APP} deliveries on behalf of one recipient. Takes
 * {@code recipientUserId} as a plain argument, sourced by the controller from the caller's token
 * — this class has no knowledge of HTTP or the security context, which is what makes "never trust
 * a client-supplied recipient id" enforceable by reading the controller alone.
 */
@Service
class NotificationQueryService {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final String READ_METRIC = "notifications.read";

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final MeterRegistry meterRegistry;

    NotificationQueryService(
            NotificationRepository notificationRepository,
            NotificationDeliveryRepository deliveryRepository,
            MeterRegistry meterRegistry) {
        this.notificationRepository = notificationRepository;
        this.deliveryRepository = deliveryRepository;
        this.meterRegistry = meterRegistry;
    }

    PageResponse<NotificationDto> list(String recipientUserId, PageQuery pageQuery, boolean unreadOnly) {
        Pageable pageable = pageQuery.toPageable(DEFAULT_SORT);
        Page<NotificationDelivery> deliveries = unreadOnly
                ? deliveryRepository.findByRecipientAndChannelAndReadAtIsNullOrderByCreatedAtDesc(
                        recipientUserId, Channel.IN_APP, pageable)
                : deliveryRepository.findByRecipientAndChannelOrderByCreatedAtDesc(
                        recipientUserId, Channel.IN_APP, pageable);

        Map<UUID, Notification> notificationsById = notificationsById(deliveries.getContent());
        return PageResponse.of(
                deliveries, delivery -> toDto(delivery, notificationsById.get(delivery.getNotificationId())));
    }

    Optional<NotificationDto> get(String recipientUserId, UUID deliveryId) {
        return deliveryRepository
                .findByIdAndRecipientAndChannel(deliveryId, recipientUserId, Channel.IN_APP)
                .map(delivery -> toDto(delivery, parentNotification(delivery)));
    }

    @Transactional
    void markRead(String recipientUserId, UUID deliveryId) {
        deliveryRepository
                .findByIdAndRecipientAndChannel(deliveryId, recipientUserId, Channel.IN_APP)
                .ifPresent(delivery -> {
                    boolean wasUnread = delivery.getReadAt() == null;
                    delivery.markRead();
                    deliveryRepository.save(delivery);
                    if (wasUnread) {
                        meterRegistry.counter(READ_METRIC).increment();
                    }
                });
    }

    @Transactional
    void markAllRead(String recipientUserId) {
        deliveryRepository.markAllReadFor(recipientUserId, Channel.IN_APP);
    }

    long unreadCount(String recipientUserId) {
        return deliveryRepository.countByRecipientAndChannelAndReadAtIsNull(recipientUserId, Channel.IN_APP);
    }

    private Notification parentNotification(NotificationDelivery delivery) {
        return notificationRepository.findById(delivery.getNotificationId()).orElseThrow();
    }

    private Map<UUID, Notification> notificationsById(List<NotificationDelivery> deliveries) {
        List<UUID> notificationIds = deliveries.stream()
                .map(NotificationDelivery::getNotificationId)
                .distinct()
                .toList();
        return notificationRepository.findAllById(notificationIds).stream()
                .collect(Collectors.toMap(Notification::getId, Function.identity()));
    }

    private static NotificationDto toDto(NotificationDelivery delivery, Notification notification) {
        return new NotificationDto(
                delivery.getId(),
                notification.getRenderedTitle(),
                notification.getRenderedBody(),
                delivery.getReadAt() != null,
                delivery.getSentAt(),
                delivery.getReadAt());
    }
}
