package io.pallet.orgteam.app;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "apps")
public class App {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private String orgId;

    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "cloud_provider", nullable = false, updatable = false)
    private CloudProvider cloudProvider;

    @Column(name = "region", nullable = false, updatable = false)
    private String region;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AppStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected App() {}

    public App(
            UUID id,
            String orgId,
            UUID teamId,
            String name,
            String slug,
            CloudProvider cloudProvider,
            String region,
            Instant now) {
        this.id = id;
        this.orgId = orgId;
        this.teamId = teamId;
        this.name = name;
        this.slug = slug;
        this.cloudProvider = cloudProvider;
        this.region = region;
        this.status = AppStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rename(String newName, Instant now) {
        this.name = newName;
        this.updatedAt = now;
    }

    public void moveToTeam(UUID newTeamId, Instant now) {
        this.teamId = newTeamId;
        this.updatedAt = now;
    }

    public void markDeleted(Instant now) {
        this.status = AppStatus.DELETED;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getOrgId() {
        return orgId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public CloudProvider getCloudProvider() {
        return cloudProvider;
    }

    public String getRegion() {
        return region;
    }

    public AppStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
