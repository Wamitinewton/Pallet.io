package io.pallet.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One delivery attempt of a {@link Notification} on one channel to one recipient.
 * {@code status} and {@code attemptCount} are the only mutable fields, changed only through
 * the named transition methods below so a channel can't leave the row in an invalid combination
 * (e.g. {@code sentAt} set while {@code status} stays {@code PENDING}).
 */
@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    @Id
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false)
    private Channel channel;

    @Column(name = "recipient", nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationDelivery() {}

    public NotificationDelivery(UUID notificationId, Channel channel, String recipient) {
        this.id = UUID.randomUUID();
        this.notificationId = notificationId;
        this.channel = channel;
        this.recipient = recipient;
        this.status = DeliveryStatus.PENDING;
        this.attemptCount = 0;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markSent() {
        this.status = DeliveryStatus.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
        this.attemptCount++;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = DeliveryStatus.FAILED;
        this.lastError = error;
        this.attemptCount++;
        this.updatedAt = Instant.now();
    }

    public void markRead() {
        if (readAt != null) {
            return;
        }
        this.readAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getRecipient() {
        return recipient;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
