package io.pallet.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The rendered content of a notification, immutable after creation. {@code sourceEventId}
 * carries the unique constraint that {@code NotificationEventIdempotencyGuard} relies on.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "notification_type", nullable = false)
    private String notificationType;

    @Column(name = "source_event_id", nullable = false)
    private UUID sourceEventId;

    @Column(name = "dedupe_key")
    private String dedupeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false)
    private Audience audience;

    @Column(name = "rendered_title", nullable = false)
    private String renderedTitle;

    @Column(name = "rendered_body", nullable = false)
    private String renderedBody;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false)
    private Map<String, Object> variables;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {}

    public Notification(
            String orgId,
            String notificationType,
            UUID sourceEventId,
            String dedupeKey,
            Audience audience,
            String renderedTitle,
            String renderedBody,
            Map<String, Object> variables) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.notificationType = notificationType;
        this.sourceEventId = sourceEventId;
        this.dedupeKey = dedupeKey;
        this.audience = audience;
        this.renderedTitle = renderedTitle;
        this.renderedBody = renderedBody;
        this.variables = variables == null ? Map.of() : Map.copyOf(variables);
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getOrgId() {
        return orgId;
    }

    public String getNotificationType() {
        return notificationType;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public Audience getAudience() {
        return audience;
    }

    public String getRenderedTitle() {
        return renderedTitle;
    }

    public String getRenderedBody() {
        return renderedBody;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
