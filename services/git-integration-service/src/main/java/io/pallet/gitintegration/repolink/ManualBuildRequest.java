package io.pallet.gitintegration.repolink;

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

/** One accepted manual build, kept so a retry with the same {@code Idempotency-Key} replays it. */
@Entity
@Immutable
@Table(name = "manual_build_requests")
public class ManualBuildRequest {

    @EmbeddedId
    private Key key;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private String branch;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "commit_sha", nullable = false, length = 40)
    private String commitSha;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ManualBuildRequest() {}

    public UUID appId() {
        return key.appId;
    }

    public String idempotencyKey() {
        return key.idempotencyKey;
    }

    public String orgId() {
        return orgId;
    }

    public String requestHash() {
        return requestHash;
    }

    public UUID eventId() {
        return eventId;
    }

    public String branch() {
        return branch;
    }

    public String commitSha() {
        return commitSha;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "app_id", nullable = false)
        private UUID appId;

        @Column(name = "idempotency_key", nullable = false)
        private String idempotencyKey;

        protected Key() {}

        public Key(UUID appId, String idempotencyKey) {
            this.appId = appId;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && appId.equals(key.appId) && idempotencyKey.equals(key.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(appId, idempotencyKey);
        }
    }
}
