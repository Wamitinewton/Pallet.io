package io.pallet.orgteam.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @Column(name = "org_id", nullable = false, updatable = false)
    private String orgId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Column(name = "owner_user_id", nullable = false)
    private String ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrgStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "purged_at")
    private Instant purgedAt;

    protected Organization() {}

    public Organization(String orgId, String name, String slug, String ownerUserId, Instant now) {
        this.orgId = orgId;
        this.name = name;
        this.slug = slug;
        this.ownerUserId = ownerUserId;
        this.status = OrgStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rename(String newName, Instant now) {
        this.name = newName;
        this.updatedAt = now;
    }

    public void markDeleted(String actorUserId, Instant now) {
        this.status = OrgStatus.DELETED;
        this.deletedAt = now;
        this.deletedBy = actorUserId;
        this.updatedAt = now;
    }

    public void transferOwnership(String newOwnerUserId, Instant now) {
        this.ownerUserId = newOwnerUserId;
        this.updatedAt = now;
    }

    public String getOrgId() {
        return orgId;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public OrgStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public String getDeletedBy() {
        return deletedBy;
    }

    public Instant getPurgedAt() {
        return purgedAt;
    }
}
