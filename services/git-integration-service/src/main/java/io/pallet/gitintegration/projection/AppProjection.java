package io.pallet.gitintegration.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** An app as org-team-service last announced it. Written only by {@link AppEventListener}. */
@Entity
@Immutable
@Table(name = "apps")
public class AppProjection {

    public enum Status {
        ACTIVE,
        DELETED
    }

    @Id
    @Column(name = "app_id")
    private UUID appId;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(nullable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppProjection() {}

    public UUID appId() {
        return appId;
    }

    public String orgId() {
        return orgId;
    }

    public String slug() {
        return slug;
    }

    public Status status() {
        return status;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
