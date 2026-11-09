package io.pallet.build.projection;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface OrgProjectionRepository extends Repository<OrgProjection, String> {

    Optional<OrgProjection> findByOrgId(String orgId);

    /** The org, share-locked until the caller's transaction ends; see {@code AppProjectionRepository#lockShared}. */
    @Query(value = "SELECT * FROM build.orgs WHERE org_id = :orgId FOR SHARE", nativeQuery = true)
    Optional<OrgProjection> lockShared(@Param("orgId") String orgId);

    /** Records an org on first sight; a known org, deleted or not, is left alone. */
    @Modifying
    @Query(value = """
            INSERT INTO build.orgs (org_id, status)
            VALUES (:orgId, 'ACTIVE')
            ON CONFLICT (org_id) DO NOTHING
            """, nativeQuery = true)
    void ensureExists(@Param("orgId") String orgId);

    /** Marks the org deleted, inserting it as deleted when unknown. The first deletion time is kept. */
    @Modifying
    @Query(value = """
            INSERT INTO build.orgs (org_id, status, deleted_at)
            VALUES (:orgId, 'DELETED', now())
            ON CONFLICT (org_id) DO UPDATE
               SET status = 'DELETED',
                   deleted_at = COALESCE(build.orgs.deleted_at, EXCLUDED.deleted_at),
                   updated_at = now()
            """, nativeQuery = true)
    void upsertDeleted(@Param("orgId") String orgId);
}
