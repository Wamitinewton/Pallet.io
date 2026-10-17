package io.pallet.buildqueue.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** An org this service has seen, its deletion tombstone, and the round robin's cursor. */
@Entity
@Immutable
@Table(name = "orgs")
public class OrgProjection {

    public enum Status {
        ACTIVE,
        DELETED
    }

    @Id
    @Column(name = "org_id")
    private String orgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "last_served_at")
    private Instant lastServedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrgProjection() {}

    public String orgId() {
        return orgId;
    }

    public Status status() {
        return status;
    }

    public Instant lastServedAt() {
        return lastServedAt;
    }

    public Instant deletedAt() {
        return deletedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
