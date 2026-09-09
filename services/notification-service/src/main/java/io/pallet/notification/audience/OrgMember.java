package io.pallet.notification.audience;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "org_members")
@IdClass(OrgMemberId.class)
public class OrgMember {

    @Id
    @Column(name = "org_id")
    private String orgId;

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "email", nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrgMemberStatus status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrgMember() {}

    public OrgMember(String orgId, String userId, String email, OrgMemberStatus status) {
        this.orgId = orgId;
        this.userId = userId;
        this.email = email;
        this.status = status;
        this.updatedAt = Instant.now();
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

    public OrgMemberStatus getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
