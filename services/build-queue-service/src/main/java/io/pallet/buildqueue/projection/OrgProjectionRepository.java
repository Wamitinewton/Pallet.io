package io.pallet.buildqueue.projection;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface OrgProjectionRepository extends Repository<OrgProjection, String> {

    Optional<OrgProjection> findByOrgId(String orgId);

    /** Records an org on first sight; a known org, deleted or not, is left alone. */
    @Modifying
    @Query(value = """
            INSERT INTO build_queue.orgs (org_id, status)
            VALUES (:orgId, 'ACTIVE')
            ON CONFLICT (org_id) DO NOTHING
            """, nativeQuery = true)
    void ensureExists(@Param("orgId") String orgId);

    /**
     * Row-locks the org until the caller's transaction ends. Intake, cancellation and the dispatcher all take this lock
     * before any build row, so they serialize per org and can't deadlock with each other.
     */
    @Query(value = "SELECT * FROM build_queue.orgs WHERE org_id = :orgId FOR UPDATE", nativeQuery = true)
    Optional<OrgProjection> lockByOrgId(@Param("orgId") String orgId);

    /** Sends the org to the back of the dispatcher's round robin. Call with the org row locked. */
    @Modifying
    @Query(value = "UPDATE build_queue.orgs SET last_served_at = now() WHERE org_id = :orgId", nativeQuery = true)
    void markServed(@Param("orgId") String orgId);

    /** Marks the org deleted, inserting it as deleted when unknown. The first deletion time is kept. */
    @Modifying
    @Query(value = """
            INSERT INTO build_queue.orgs (org_id, status, deleted_at)
            VALUES (:orgId, 'DELETED', now())
            ON CONFLICT (org_id) DO UPDATE
               SET status = 'DELETED',
                   deleted_at = COALESCE(build_queue.orgs.deleted_at, EXCLUDED.deleted_at),
                   updated_at = now()
            """, nativeQuery = true)
    void upsertDeleted(@Param("orgId") String orgId);
}
