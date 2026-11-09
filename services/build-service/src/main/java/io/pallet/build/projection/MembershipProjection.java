package io.pallet.build.projection;

import io.pallet.build.security.Role;
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

/** One membership as the compacted {@code org.membership.changed} topic last stated it. */
@Entity
@Immutable
@Table(name = "org_memberships")
public class MembershipProjection {

    public enum Status {
        ACTIVE,
        REMOVED
    }

    @EmbeddedId
    private Key key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "source_version", nullable = false)
    private long sourceVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MembershipProjection() {}

    public String orgId() {
        return key.orgId;
    }

    public String userId() {
        return key.userId;
    }

    public Role role() {
        return role;
    }

    public Status status() {
        return status;
    }

    public long sourceVersion() {
        return sourceVersion;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "org_id", nullable = false)
        private String orgId;

        @Column(name = "user_id", nullable = false)
        private String userId;

        protected Key() {}

        public Key(String orgId, String userId) {
            this.orgId = orgId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that && orgId.equals(that.orgId) && userId.equals(that.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orgId, userId);
        }
    }
}
