package io.pallet.identity.signup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A write-once sign-up bootstrap snapshot, not a live-synced copy of the organization —
 * {@code org-team-service} becomes the canonical, mutable owner of an org's name and settings the
 * moment it consumes {@code OrgProvisioned}. Deliberately not named {@code Organization} so a
 * reader doesn't mistake it for that resource. No update methods: nothing mutates this after
 * insert.
 */
@Entity
@Table(name = "organizations")
public class OrgBootstrapRecord {

    @Id
    @Column(name = "org_id")
    private String orgId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "owner_user_id", nullable = false)
    private String ownerUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrgBootstrapRecord() {}

    public OrgBootstrapRecord(String orgId, String name, String slug, String ownerUserId) {
        this.orgId = orgId;
        this.name = name;
        this.slug = slug;
        this.ownerUserId = ownerUserId;
        this.createdAt = Instant.now();
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
