package io.pallet.orgteam.invite;

import io.pallet.orgteam.member.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "invites")
public class Invite implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private String orgId;

    @Column(name = "email", nullable = false, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false)
    private Role role;

    @Column(name = "invited_by_user_id", nullable = false, updatable = false)
    private String invitedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InviteStatus status;

    @Column(name = "send_count", nullable = false)
    private int sendCount;

    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean fresh;

    protected Invite() {}

    public Invite(
            UUID id, String orgId, String email, Role role, String invitedByUserId, Instant now, Instant expiresAt) {
        this.id = id;
        this.orgId = orgId;
        this.email = email;
        this.role = role;
        this.invitedByUserId = invitedByUserId;
        this.status = InviteStatus.PENDING;
        this.sendCount = 1;
        this.lastSentAt = now;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.fresh = true;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.fresh = false;
    }

    public InviteStatus effectiveStatus(Instant now) {
        return status == InviteStatus.PENDING && !expiresAt.isAfter(now) ? InviteStatus.EXPIRED : status;
    }

    public boolean isLive(Instant now) {
        return effectiveStatus(now) == InviteStatus.PENDING;
    }

    public void expire(Instant now) {
        this.status = InviteStatus.EXPIRED;
        this.respondedAt = now;
    }

    public void accept(Instant now) {
        this.status = InviteStatus.ACCEPTED;
        this.respondedAt = now;
    }

    public void revoke(Instant now) {
        this.status = InviteStatus.REVOKED;
        this.respondedAt = now;
    }

    public void resend(Instant now, Instant newExpiresAt) {
        this.sendCount++;
        this.lastSentAt = now;
        this.expiresAt = newExpiresAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getOrgId() {
        return orgId;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    public String getInvitedByUserId() {
        return invitedByUserId;
    }

    public InviteStatus getStatus() {
        return status;
    }

    public int getSendCount() {
        return sendCount;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
