package io.pallet.notification.repository;

import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.NotificationDelivery;
import java.util.List;
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

    Page<NotificationDelivery> findByRecipientAndChannelAndReadAtIsNullOrderByCreatedAtDesc(
            String recipient, Channel channel, Pageable pageable);

    Optional<NotificationDelivery> findByIdAndRecipientAndChannel(UUID id, String recipient, Channel channel);

    long countByRecipientAndChannelAndReadAtIsNull(String recipient, Channel channel);

    /**
     * Bulk-clears every unread delivery for a recipient on one channel in a single statement,
     * avoiding a load-then-save per row when the unread count is large.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE NotificationDelivery d
            SET d.readAt = CURRENT_TIMESTAMP, d.updatedAt = CURRENT_TIMESTAMP
            WHERE d.recipient = :recipient AND d.channel = :channel AND d.readAt IS NULL
            """)
    void markAllReadFor(@Param("recipient") String recipient, @Param("channel") Channel channel);

    @Transactional
    @Query(value = """
                    WITH claimed AS (
                        UPDATE notification.notification_deliveries
                        SET status = 'PENDING', updated_at = now()
                        WHERE id IN (
                            SELECT id FROM notification.notification_deliveries
                            WHERE status = 'THROTTLED'
                            ORDER BY created_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING id, created_at)
                    SELECT id FROM claimed ORDER BY created_at
                    """, nativeQuery = true)
    List<UUID> claimThrottled(@Param("limit") int limit);

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
