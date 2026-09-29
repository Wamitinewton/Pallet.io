package io.pallet.gitintegration.push;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The last commit accepted as the head of an app's branch: what the chain rule compares an incoming push against. */
@Entity
@Immutable
@Table(name = "branch_heads")
public class BranchHead {

    public static final int MAX_ETAG_LENGTH = 128;

    @EmbeddedId
    private Key key;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "head_sha", nullable = false, length = 40)
    private String headSha;

    @Column(name = "advanced_at", nullable = false)
    private Instant advancedAt;

    @Column(length = MAX_ETAG_LENGTH)
    private String etag;

    protected BranchHead() {}

    public UUID appId() {
        return key.appId;
    }

    public String branch() {
        return key.branch;
    }

    public String headSha() {
        return headSha;
    }

    public Instant advancedAt() {
        return advancedAt;
    }

    public String etag() {
        return etag;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "app_id", nullable = false)
        private UUID appId;

        @Column(nullable = false)
        private String branch;

        protected Key() {}

        public Key(UUID appId, String branch) {
            this.appId = appId;
            this.branch = branch;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && appId.equals(key.appId) && branch.equals(key.branch);
        }

        @Override
        public int hashCode() {
            return Objects.hash(appId, branch);
        }
    }
}
