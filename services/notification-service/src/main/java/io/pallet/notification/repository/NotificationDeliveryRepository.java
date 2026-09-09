package io.pallet.notification.repository;

import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.NotificationDelivery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    Optional<NotificationDelivery> findByNotificationIdAndChannelAndRecipient(
            UUID notificationId, Channel channel, String recipient);

    Page<NotificationDelivery> findByRecipientAndChannelOrderByCreatedAtDesc(
            String recipient, Channel channel, Pageable pageable);

    long countByRecipientAndChannelAndReadAtIsNull(String recipient, Channel channel);
}
