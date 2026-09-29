package io.pallet.gitintegration.repolink;

import io.pallet.gitintegration.push.PushTarget;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Which repository, branch, and directory an app builds from, and the GitHub user who vouched for it. Written only
 * through {@link RepoLinkRepository}'s statements, so every change is one guarded {@code UPDATE}.
 */
@Entity
@Immutable
@Table(name = "repo_links")
public class RepoLink implements PushTarget {

    public enum Status {
        ACTIVE,
        DISCONNECTED
    }

    /** Stored as {@code repo_links.disconnect_reason} and in the audit record. */
    public enum DisconnectReason {
        UNLINKED_BY_USER,
        INSTALLATION_UNLINKED,
        INSTALLATION_DELETED,
        REPOSITORY_ACCESS_REMOVED,
        REPOSITORY_DELETED,
        APP_DELETED,
        ORG_DELETED,
        VERIFIER_ACCESS_LOST
    }

    @Id
    @Column(name = "app_id")
    private UUID appId;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "installation_id", nullable = false)
    private long installationId;

    @Column(name = "repo_id", nullable = false)
    private long repoId;

    @Column(name = "repo_full_name", nullable = false)
    private String repoFullName;

    @Column(name = "production_branch", nullable = false)
    private String productionBranch;

    @Column(name = "root_directory")
    private String rootDirectory;

    @Column(name = "auto_deploy", nullable = false)
    private boolean autoDeploy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "disconnect_reason")
    private String disconnectReason;

    @Column(name = "verified_by_user_id")
    private String verifiedByUserId;

    @Column(name = "verified_github_user_id")
    private Long verifiedGithubUserId;

    @Column(name = "verified_github_login")
    private String verifiedGithubLogin;

    @Column(name = "verified_permission")
    private String verifiedPermission;

    @Column(name = "access_verified_at")
    private Instant accessVerifiedAt;

    @Column(name = "access_checked_at")
    private Instant accessCheckedAt;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "disconnected_at")
    private Instant disconnectedAt;

    protected RepoLink() {}

    @Override
    public UUID appId() {
        return appId;
    }

    @Override
    public String orgId() {
        return orgId;
    }

    @Override
    public long installationId() {
        return installationId;
    }

    @Override
    public long repoId() {
        return repoId;
    }

    @Override
    public String repoFullName() {
        return repoFullName;
    }

    @Override
    public String productionBranch() {
        return productionBranch;
    }

    @Override
    public String rootDirectory() {
        return rootDirectory;
    }

    public boolean autoDeploy() {
        return autoDeploy;
    }

    public Status status() {
        return status;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public String disconnectReason() {
        return disconnectReason;
    }

    public String verifiedByUserId() {
        return verifiedByUserId;
    }

    public Long verifiedGithubUserId() {
        return verifiedGithubUserId;
    }

    public String verifiedGithubLogin() {
        return verifiedGithubLogin;
    }

    public String verifiedPermission() {
        return verifiedPermission;
    }

    public Instant accessVerifiedAt() {
        return accessVerifiedAt;
    }

    public Instant accessCheckedAt() {
        return accessCheckedAt;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant disconnectedAt() {
        return disconnectedAt;
    }
}
