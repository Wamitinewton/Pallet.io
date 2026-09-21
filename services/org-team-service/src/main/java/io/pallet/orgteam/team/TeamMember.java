package io.pallet.orgteam.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "team_members")
@IdClass(TeamMemberId.class)
public class TeamMember implements Persistable<TeamMemberId> {

    @Id
    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private String orgId;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @Column(name = "added_by", nullable = false, updatable = false)
    private String addedBy;

    @Transient
    private boolean fresh;

    protected TeamMember() {}

    public TeamMember(UUID teamId, String userId, String orgId, String addedBy, Instant addedAt) {
        this.teamId = teamId;
        this.userId = userId;
        this.orgId = orgId;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
        this.fresh = true;
    }

    @Override
    public TeamMemberId getId() {
        return new TeamMemberId(teamId, userId);
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

    public UUID getTeamId() {
        return teamId;
    }

    public String getUserId() {
        return userId;
    }

    public String getOrgId() {
        return orgId;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public String getAddedBy() {
        return addedBy;
    }
}
