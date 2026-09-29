package io.pallet.gitintegration.projection;

import io.pallet.gitintegration.config.CrossTenant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AppProjectionRepository extends Repository<AppProjection, UUID> {

    Optional<AppProjection> findByOrgIdAndAppId(String orgId, UUID appId);

    List<AppProjection> findByOrgIdAndAppIdIn(String orgId, Collection<UUID> appIds);

    /** @return the app's status, share-locked so a deletion waits for anything being attached to the app */
    @Query(value = """
            SELECT status FROM git_integration.apps WHERE org_id = :orgId AND app_id = :appId FOR SHARE
            """, nativeQuery = true)
    Optional<String> shareStatus(@Param("orgId") String orgId, @Param("appId") UUID appId);

    /** @return 1 if inserted, 0 if the app was already known, in any state */
    @Modifying
    @Query(value = """
            INSERT INTO git_integration.apps (app_id, org_id, slug, status, updated_at)
            VALUES (:appId, :orgId, :slug, 'ACTIVE', now())
            ON CONFLICT (app_id) DO NOTHING
            """, nativeQuery = true)
    int insertActiveIfAbsent(@Param("orgId") String orgId, @Param("appId") UUID appId, @Param("slug") String slug);

    /**
     * Marks the app deleted, inserting it as deleted when unknown so a late {@code app.created} can't resurrect it.
     *
     * @return 0 only when the app is known under another org
     */
    @Modifying
    @Query(value = """
            INSERT INTO git_integration.apps (app_id, org_id, slug, status, updated_at)
            VALUES (:appId, :orgId, :slug, 'DELETED', now())
            ON CONFLICT (app_id) DO UPDATE
               SET status = 'DELETED', updated_at = now()
             WHERE git_integration.apps.org_id = EXCLUDED.org_id
            """, nativeQuery = true)
    int upsertDeleted(@Param("orgId") String orgId, @Param("appId") UUID appId, @Param("slug") String slug);

    @CrossTenant("detects one app id announced under two orgs, which must never move an app between tenants")
    @Query(value = "SELECT org_id FROM git_integration.apps WHERE app_id = :appId", nativeQuery = true)
    Optional<String> findOwningOrgId(@Param("appId") UUID appId);
}
