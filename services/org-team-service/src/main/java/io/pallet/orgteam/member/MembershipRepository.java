package io.pallet.orgteam.member;

import io.pallet.orgteam.config.CrossTenant;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

    @Query("""
            select new io.pallet.orgteam.member.MemberAccess(o.status, m.role, m.status)
            from Organization o
            left join Membership m on m.orgId = o.orgId and m.userId = :userId
            where o.orgId = :orgId
            """)
    Optional<MemberAccess> findAccess(String orgId, String userId);

    Optional<Membership> findByOrgIdAndUserId(String orgId, String userId);

    Optional<Membership> findByOrgIdAndUserIdAndStatus(String orgId, String userId, MembershipStatus status);

    @Query("select m.status from Membership m where m.orgId = :orgId and lower(m.email) = lower(:email)")
    List<MembershipStatus> findStatusesByEmail(String orgId, String email);

    @Query("""
            select count(m) > 0 from Membership m
            where m.orgId = :orgId and lower(m.email) = lower(:email) and m.status = :status
            """)
    boolean existsByOrgIdAndEmailIgnoreCaseAndStatus(String orgId, String email, MembershipStatus status);

    @Query("""
            select count(m) > 0 from Membership m
            where m.orgId = :orgId and lower(m.email) = lower(:email) and m.status = :status and m.userId <> :userId
            """)
    boolean existsByOrgIdAndEmailIgnoreCaseAndStatusAndUserIdNot(
            String orgId, String email, MembershipStatus status, String userId);

    @Query("""
            select m from Membership m
            where m.orgId = :orgId
              and m.status = :status
              and m.role in :roles
              and (lower(m.email) like :prefix escape '!' or lower(m.displayName) like :prefix escape '!')
            """)
    Page<Membership> search(
            String orgId, MembershipStatus status, Collection<Role> roles, String prefix, Pageable pageable);

    @Modifying
    @Query(value = "DELETE FROM org_team.team_members WHERE org_id = :orgId AND user_id = :userId", nativeQuery = true)
    void deleteTeamAssignments(String orgId, String userId);

    @Modifying
    @Query(value = """
            UPDATE org_team.memberships
            SET status = 'REMOVED', removed_at = :now, removed_by = :actorUserId, version = version + 1
            WHERE org_id = :orgId AND status = 'ACTIVE'
            """, nativeQuery = true)
    void removeAllActive(String orgId, String actorUserId, Instant now);

    @CrossTenant("retention sweep spans every organization")
    @Modifying
    @Query(value = """
            DELETE FROM org_team.memberships
            WHERE ctid IN (
                SELECT ctid FROM org_team.memberships
                WHERE status = 'REMOVED' AND removed_at < now() - make_interval(secs => :windowSeconds)
                ORDER BY removed_at
                LIMIT :batchSize)
            """, nativeQuery = true)
    int deleteRemovedOlderThan(double windowSeconds, int batchSize);

    @Modifying
    @Query("delete from Membership m where m.orgId = :orgId")
    int deleteAllForOrg(String orgId);
}
