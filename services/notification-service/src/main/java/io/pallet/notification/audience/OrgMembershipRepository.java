package io.pallet.notification.audience;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The org-member projection behind {@code ORG} broadcasts, maintained from org-team-service's
 * membership events. Every write applies only when its event is at least as new as the row's
 * {@code updated_at}, so a redelivered or reordered event resolves to the newest fact.
 */
public interface OrgMembershipRepository extends JpaRepository<OrgMember, OrgMemberId> {

    List<OrgMember> findByOrgIdAndStatus(String orgId, OrgMemberStatus status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    INSERT INTO notification.org_members (org_id, user_id, email, status, updated_at)
                    VALUES (:orgId, :userId, :email, 'ACTIVE', :occurredAt)
                    ON CONFLICT (org_id, user_id) DO UPDATE
                    SET email = EXCLUDED.email, status = 'ACTIVE', updated_at = EXCLUDED.updated_at
                    WHERE notification.org_members.updated_at <= EXCLUDED.updated_at
                    """, nativeQuery = true)
    int upsertActive(
            @Param("orgId") String orgId,
            @Param("userId") String userId,
            @Param("email") String email,
            @Param("occurredAt") Instant occurredAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    INSERT INTO notification.org_members (org_id, user_id, email, status, updated_at)
                    VALUES (:orgId, :userId, :email, 'REMOVED', :occurredAt)
                    ON CONFLICT (org_id, user_id) DO UPDATE
                    SET status = 'REMOVED', updated_at = EXCLUDED.updated_at
                    WHERE notification.org_members.updated_at <= EXCLUDED.updated_at
                    """, nativeQuery = true)
    int markRemoved(
            @Param("orgId") String orgId,
            @Param("userId") String userId,
            @Param("email") String email,
            @Param("occurredAt") Instant occurredAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    UPDATE notification.org_members
                    SET status = 'REMOVED', updated_at = :occurredAt
                    WHERE org_id = :orgId AND updated_at <= :occurredAt
                    """, nativeQuery = true)
    int markAllRemovedForOrg(@Param("orgId") String orgId, @Param("occurredAt") Instant occurredAt);
}
