package io.pallet.buildqueue.projection;

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

    /** Always exactly one row, so a deleted org is reported as such whether or not a membership exists. */
    @Query(value = """
            SELECT m.role AS role, m.status AS status, COALESCE(o.status = 'DELETED', false) AS deleted
              FROM (SELECT CAST(:orgId AS TEXT) AS org_id) requested
              LEFT JOIN build_queue.org_memberships m ON m.org_id = requested.org_id AND m.user_id = :userId
              LEFT JOIN build_queue.orgs o ON o.org_id = requested.org_id
            """, nativeQuery = true)
    MembershipAccess findAccess(@Param("orgId") String orgId, @Param("userId") String userId);

    /**
     * Applies one state record. The conflict branch's {@code WHERE} is the ordering guarantee: an older or equal
     * version is a no-op in the database, under any concurrency.
     *
     * @return 1 if the record was applied, 0 if it was stale
     */
    @Modifying
    @Query(value = """
            INSERT INTO build_queue.org_memberships (org_id, user_id, role, status, source_version)
            VALUES (:orgId, :userId, :role, :status, :version)
            ON CONFLICT (org_id, user_id) DO UPDATE
               SET role = EXCLUDED.role, status = EXCLUDED.status, source_version = EXCLUDED.source_version
             WHERE build_queue.org_memberships.source_version < EXCLUDED.source_version
            """, nativeQuery = true)
    int applyState(
            @Param("orgId") String orgId,
            @Param("userId") String userId,
            @Param("role") String role,
            @Param("status") String status,
            @Param("version") long version);

    @Modifying
    @Query(value = """
            DELETE FROM build_queue.org_memberships
             WHERE org_id = :orgId AND user_id = :userId
            """, nativeQuery = true)
    void deleteMembership(@Param("orgId") String orgId, @Param("userId") String userId);

    /** Leaves {@code source_version} alone so the authoritative {@code REMOVED} state records still apply. */
    @Modifying
    @Query(value = """
            UPDATE build_queue.org_memberships
               SET status = 'REMOVED'
             WHERE org_id = :orgId AND status <> 'REMOVED'
            """, nativeQuery = true)
    int markAllRemoved(@Param("orgId") String orgId);
}
