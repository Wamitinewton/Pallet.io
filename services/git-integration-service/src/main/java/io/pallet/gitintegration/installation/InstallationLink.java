package io.pallet.gitintegration.installation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/** One org's connection to one installation, created by a member who proved on GitHub that they can see it. */
@Entity
@Immutable
@Table(name = "installation_links")
public class InstallationLink {

    public enum Status {
        ACTIVE,
        UNLINKED
    }

    @EmbeddedId
    private Key key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "linked_by_user_id", nullable = false)
    private String linkedByUserId;

    @Column(name = "linked_by_github_user_id", nullable = false)
    private long linkedByGithubUserId;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "unlinked_at")
    private Instant unlinkedAt;

    @Column(nullable = false)
    private long version;

    protected InstallationLink() {}

    public long installationId() {
        return key.installationId;
    }

    public String orgId() {
        return key.orgId;
    }

    public Status status() {
        return status;
    }

    public String linkedByUserId() {
        return linkedByUserId;
    }

    public long linkedByGithubUserId() {
        return linkedByGithubUserId;
    }

    public Instant linkedAt() {
        return linkedAt;
    }

    public Instant unlinkedAt() {
        return unlinkedAt;
    }

    public long version() {
        return version;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "installation_id", nullable = false)
        private long installationId;

        @Column(name = "org_id", nullable = false)
        private String orgId;

        protected Key() {}

        public Key(long installationId, String orgId) {
            this.installationId = installationId;
            this.orgId = orgId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && installationId == key.installationId && orgId.equals(key.orgId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(installationId, orgId);
        }
    }
}
