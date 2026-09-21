package io.pallet.orgteam.team;

import io.pallet.orgteam.member.Membership;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMemberId> {

    boolean existsByOrgIdAndTeamIdAndUserId(String orgId, UUID teamId, String userId);

    long countByOrgIdAndTeamId(String orgId, UUID teamId);

    @Query("""
            select new io.pallet.orgteam.team.TeamMemberCount(tm.teamId, count(tm.userId))
            from TeamMember tm
            where tm.orgId = :orgId and tm.teamId in :teamIds
            group by tm.teamId
            """)
    List<TeamMemberCount> countByTeams(String orgId, Collection<UUID> teamIds);

    @Query("""
            select m from Membership m
            where m.orgId = :orgId
              and m.status = io.pallet.orgteam.member.MembershipStatus.ACTIVE
              and exists (
                  select 1 from TeamMember tm
                  where tm.orgId = :orgId and tm.teamId = :teamId and tm.userId = m.userId)
            """)
    Page<Membership> findActiveMembers(String orgId, UUID teamId, Pageable pageable);

    @Modifying
    @Query("delete from TeamMember tm where tm.orgId = :orgId and tm.teamId = :teamId and tm.userId = :userId")
    int deleteAssignment(String orgId, UUID teamId, String userId);

    @Modifying
    @Query("delete from TeamMember tm where tm.orgId = :orgId")
    int deleteAllForOrg(String orgId);
}
