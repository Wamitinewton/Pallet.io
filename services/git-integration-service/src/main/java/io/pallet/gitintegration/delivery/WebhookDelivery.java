package io.pallet.gitintegration.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One GitHub webhook delivery, keyed by {@code X-GitHub-Delivery}. Inserted only by {@link DeliveryStore} and moved
 * only by {@link DeliveryRepository}'s guarded statements.
 */
@Entity
@Immutable
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    @Id
    @Column(name = "delivery_id")
    private UUID deliveryId;

    @Column(nullable = false)
    private String event;

    @Column
    private String action;

    @Column(name = "installation_id")
    private Long installationId;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;

    @Column(name = "outcome_reason")
    private String outcomeReason;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column
    private String traceparent;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected WebhookDelivery() {}

    public UUID deliveryId() {
        return deliveryId;
    }

    public String event() {
        return event;
    }

    public String action() {
        return action;
    }

    public Long installationId() {
        return installationId;
    }

    public String payload() {
        return payload;
    }

    public DeliveryStatus status() {
        return status;
    }

    public String outcomeReason() {
        return outcomeReason;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String lastError() {
        return lastError;
    }

    public String traceparent() {
        return traceparent;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Instant processedAt() {
        return processedAt;
    }
}
