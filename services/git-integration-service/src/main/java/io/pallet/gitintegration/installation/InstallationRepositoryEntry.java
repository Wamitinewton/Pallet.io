package io.pallet.gitintegration.installation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * A repository the installation can reach, as GitHub last listed it. Internal only: an installation reaches
 * repositories a given member can't, so this is never shown to a user.
 */
@Entity
@Immutable
@Table(name = "installation_repositories")
public class InstallationRepositoryEntry {

    @EmbeddedId
    private Key key;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "default_branch")
    private String defaultBranch;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    protected InstallationRepositoryEntry() {}

    public long installationId() {
        return key.installationId;
    }

    public long repoId() {
        return key.repoId;
    }

    public String fullName() {
        return fullName;
    }

    public String defaultBranch() {
        return defaultBranch;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public boolean archived() {
        return archived;
    }

    public Instant syncedAt() {
        return syncedAt;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "installation_id", nullable = false)
        private long installationId;

        @Column(name = "repo_id", nullable = false)
        private long repoId;

        protected Key() {}

        public Key(long installationId, long repoId) {
            this.installationId = installationId;
            this.repoId = repoId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && installationId == key.installationId && repoId == key.repoId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(installationId, repoId);
        }
    }
}
