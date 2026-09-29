package io.pallet.gitintegration.projection;

import io.pallet.gitintegration.config.CrossTenant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface MembershipProjectionRepository extends Repository<MembershipProjection, MembershipProjection.Key> {

    /** The gate's view of one caller in one org; {@code role} and {@code status} are null when there is no row. */
    interface MembershipAccess {

        String getRole();

        String getStatus();

        boolean getDeleted();
    }

    @Query("SELECT m FROM MembershipProjection m WHERE m.key.orgId = :orgId AND m.key.userId = :userId")
    Optional<MembershipProjection> findMembership(@Param("orgId") String orgId, @Param("userId") String userId);

    /** Always exactly one row, so an org known to be deleted is reported as such whether or not a membership exists. */
    @Query(value = """
            SELECT m.role AS role, m.status AS status, (d.org_id IS NOT NULL) AS deleted
              FROM (SELECT CAST(:orgId AS VARCHAR) AS org_id) requested
              LEFT JOIN git_integration.org_memberships m ON m.org_id = requested.org_id AND m.user_id = :userId
              LEFT JOIN git_integration.deleted_orgs d ON d.org_id = requested.org_id
            """, nativeQuery = true)
    MembershipAccess findAccess(@Param("orgId") String orgId, @Param("userId") String userId);

    @CrossTenant("A GitHub authorization belongs to a user, not an org; each org the user is active in audits its end")
    @Query(value = """
            SELECT m.org_id FROM git_integration.org_memberships m
             WHERE m.user_id = :userId AND m.status = 'ACTIVE'
               AND NOT EXISTS (SELECT 1 FROM git_integration.deleted_orgs d WHERE d.org_id = m.org_id)
             ORDER BY m.org_id
            """, nativeQuery = true)
    List<String> findActiveOrgIds(@Param("userId") String userId);

    /**
     * Applies one state record. The conflict branch's {@code WHERE} is the ordering guarantee: an older or equal
     * version is a no-op in the database, under any concurrency.
     *
     * @return 1 if the record was applied, 0 if it was stale
     */
    @Modifying
    @Query(value = """
            INSERT INTO git_integration.org_memberships (org_id, user_id, role, status, source_version, updated_at)
            VALUES (:orgId, :userId, :role, :status, :version, now())
            ON CONFLICT (org_id, user_id) DO UPDATE
               SET role = EXCLUDED.role, status = EXCLUDED.status,
                   source_version = EXCLUDED.source_version, updated_at = now()
             WHERE git_integration.org_memberships.source_version < EXCLUDED.source_version
            """, nativeQuery = true)
    int applyState(
            @Param("orgId") String orgId,
            @Param("userId") String userId,
            @Param("role") String role,
            @Param("status") String status,
            @Param("version") long version);

    @Modifying
    @Query(value = """
            DELETE FROM git_integration.org_memberships
             WHERE org_id = :orgId AND user_id = :userId
            """, nativeQuery = true)
    void deleteMembership(@Param("orgId") String orgId, @Param("userId") String userId);

    /** Leaves {@code source_version} alone so the authoritative {@code REMOVED} state records still apply. */
    @Modifying
    @Query(value = """
            UPDATE git_integration.org_memberships
               SET status = 'REMOVED', updated_at = now()
             WHERE org_id = :orgId AND status <> 'REMOVED'
            """, nativeQuery = true)
    int markAllRemoved(@Param("orgId") String orgId);
}
