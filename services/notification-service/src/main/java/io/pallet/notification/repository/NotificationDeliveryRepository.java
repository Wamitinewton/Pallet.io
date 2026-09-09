package io.pallet.notification.repository;

import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.NotificationDelivery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    Optional<NotificationDelivery> findByNotificationIdAndChannelAndRecipient(
            UUID notificationId, Channel channel, String recipient);

    Page<NotificationDelivery> findByRecipientAndChannelOrderByCreatedAtDesc(
            String recipient, Channel channel, Pageable pageable);

    long countByRecipientAndChannelAndReadAtIsNull(String recipient, Channel channel);

    /**
     * Inserts a {@code PENDING} delivery row for (notificationId, channel, recipient) unless one
     * already exists, returning the number of rows actually inserted (0 or 1). Used instead of a
     * separate existence check so a resumed or redelivered fan-out can tell "already handled" from
     * "new" without the race window a check-then-insert would have.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    INSERT INTO notification.notification_deliveries
                        (id, notification_id, channel, recipient, status, attempt_count, created_at, updated_at)
                    VALUES (:id, :notificationId, :channel, :recipient, 'PENDING', 0, now(), now())
                    ON CONFLICT (notification_id, channel, recipient) DO NOTHING
                    """, nativeQuery = true)
    int insertPendingIfAbsent(
            @Param("id") UUID id,
            @Param("notificationId") UUID notificationId,
            @Param("channel") String channel,
            @Param("recipient") String recipient);
}
