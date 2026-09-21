package io.pallet.orgteam.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.Formula;

@Entity
@Table(name = "memberships")
@IdClass(MembershipId.class)
public class Membership {

    @Id
    @Column(name = "org_id", nullable = false, updatable = false)
    private String orgId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MembershipStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Formula("case role when 'VIEWER' then 0 when 'DEVELOPER' then 1 when 'ADMIN' then 2 else 3 end")
    private int roleRank;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "removed_by")
    private String removedBy;

    @Column(name = "profile_synced_at")
    private Instant profileSyncedAt;

    protected Membership() {}

    public Membership(String orgId, String userId, String email, String displayName, Role role, Instant joinedAt) {
        this.orgId = orgId;
        this.userId = userId;
        this.email = email;
        this.displayName = displayName;
        this.role = role;
        this.status = MembershipStatus.ACTIVE;
        this.joinedAt = joinedAt;
    }

    public void changeRole(Role newRole) {
        this.role = newRole;
    }

    public void remove(Instant now, String removedByUserId) {
        this.status = MembershipStatus.REMOVED;
        this.removedAt = now;
        this.removedBy = removedByUserId;
    }

    public void syncProfile(String email, String displayName, Instant syncedAt) {
        this.email = email;
        this.displayName = displayName;
        this.profileSyncedAt = syncedAt;
    }

    public void markProfileSynced(Instant syncedAt) {
        this.profileSyncedAt = syncedAt;
    }

    public String getOrgId() {
        return orgId;
    }

    public String getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public String getRemovedBy() {
        return removedBy;
    }

    public Instant getProfileSyncedAt() {
        return profileSyncedAt;
    }
}
