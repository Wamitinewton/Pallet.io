package io.pallet.buildqueue.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** An app as org-team-service last announced it, or as a push first revealed it. */
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
    private String appId;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppProjection() {}

    public String appId() {
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

    public Instant deletedAt() {
        return deletedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
