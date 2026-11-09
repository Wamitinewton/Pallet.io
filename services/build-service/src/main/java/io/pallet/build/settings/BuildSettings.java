package io.pallet.build.settings;

import io.pallet.build.domain.SettingsSnapshot;
import io.pallet.build.domain.Strategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** An app's build settings. An app without a row builds with {@link SettingsSnapshot#DEFAULTS}. */
@Entity
@Immutable
@Table(name = "build_settings")
public class BuildSettings {

    @Id
    @Column(name = "app_id")
    private String appId;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Strategy strategy;

    @Column(name = "dockerfile_path", nullable = false)
    private String dockerfilePath;

    @Column(nullable = false)
    private long version;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BuildSettings() {}

    public String appId() {
        return appId;
    }

    public String orgId() {
        return orgId;
    }

    public Strategy strategy() {
        return strategy;
    }

    public String dockerfilePath() {
        return dockerfilePath;
    }

    public long version() {
        return version;
    }

    public String updatedBy() {
        return updatedBy;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public SettingsSnapshot snapshot() {
        return new SettingsSnapshot(strategy, dockerfilePath);
    }
}
