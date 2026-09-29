package io.pallet.gitintegration.installation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A GitHub installation of the Pallet app: GitHub's facts only, belonging to no org. Written only through
 * {@link InstallationRepository}'s upserts, so the webhook and the link redirect converge on the same row.
 */
@Entity
@Immutable
@Table(name = "installations")
public class Installation {

    public enum Status {
        ACTIVE,
        SUSPENDED,
        DELETED
    }

    @Id
    @Column(name = "installation_id")
    private long installationId;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "account_login", nullable = false)
    private String accountLogin;

    @Column(name = "account_type", nullable = false)
    private String accountType;

    @Column(name = "repository_selection", nullable = false)
    private String repositorySelection;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String permissions;

    @Column(name = "unused_since")
    private Instant unusedSince;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Installation() {}

    public long installationId() {
        return installationId;
    }

    public long accountId() {
        return accountId;
    }

    public String accountLogin() {
        return accountLogin;
    }

    public String accountType() {
        return accountType;
    }

    public String repositorySelection() {
        return repositorySelection;
    }

    public Status status() {
        return status;
    }

    public String permissions() {
        return permissions;
    }

    public Instant unusedSince() {
        return unusedSince;
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

    public Instant suspendedAt() {
        return suspendedAt;
    }

    public Instant deletedAt() {
        return deletedAt;
    }
}
