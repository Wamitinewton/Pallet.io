package io.pallet.build.projection;

import io.pallet.build.config.CrossTenant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AppProjectionRepository extends Repository<AppProjection, String> {

    Optional<AppProjection> findByOrgIdAndAppId(String orgId, String appId);

    /** @return 1 if inserted, 0 if the app was already known, in any state and under any org */
    @Modifying
    @Query(value = """
            INSERT INTO build.apps (app_id, org_id, status)
            VALUES (:appId, :orgId, 'ACTIVE')
            ON CONFLICT (app_id) DO NOTHING
            """, nativeQuery = true)
    int insertActiveIfAbsent(@Param("orgId") String orgId, @Param("appId") String appId);

    /**
     * Marks the app deleted, inserting it as deleted when unknown so a late {@code app.created} can't revive it. The
     * first deletion time is kept.
     *
     * @return 0 only when the app is known under another org
     */
    @Modifying
    @Query(value = """
            INSERT INTO build.apps (app_id, org_id, status, deleted_at)
            VALUES (:appId, :orgId, 'DELETED', now())
            ON CONFLICT (app_id) DO UPDATE
               SET status = 'DELETED',
                   deleted_at = COALESCE(build.apps.deleted_at, EXCLUDED.deleted_at),
                   updated_at = now()
             WHERE build.apps.org_id = EXCLUDED.org_id
            """, nativeQuery = true)
    int upsertDeleted(@Param("orgId") String orgId, @Param("appId") String appId);

    @CrossTenant("detects one app id announced under two orgs, which must never move an app between tenants")
    @Query(value = "SELECT org_id FROM build.apps WHERE app_id = :appId", nativeQuery = true)
    Optional<String> findOwningOrgId(@Param("appId") String appId);
}
