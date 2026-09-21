package io.pallet.orgteam.app;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AppRepository extends JpaRepository<App, UUID> {

    Optional<App> findByOrgIdAndIdAndStatus(String orgId, UUID id, AppStatus status);

    boolean existsByOrgIdAndSlugAndStatus(String orgId, String slug, AppStatus status);

    long countByOrgIdAndStatus(String orgId, AppStatus status);

    Page<App> findByOrgIdAndStatus(String orgId, AppStatus status, Pageable pageable);

    Page<App> findByOrgIdAndStatusAndTeamId(String orgId, AppStatus status, UUID teamId, Pageable pageable);

    Page<App> findByOrgIdAndStatusAndCloudProvider(
            String orgId, AppStatus status, CloudProvider cloudProvider, Pageable pageable);

    Page<App> findByOrgIdAndStatusAndTeamIdAndCloudProvider(
            String orgId, AppStatus status, UUID teamId, CloudProvider cloudProvider, Pageable pageable);

    /** Picks the query shape per filter combination so Postgres never plans around a NULL-tolerant catch-all. */
    default Page<App> search(String orgId, UUID teamId, CloudProvider cloudProvider, Pageable pageable) {
        AppStatus active = AppStatus.ACTIVE;
        if (teamId != null && cloudProvider != null) {
            return findByOrgIdAndStatusAndTeamIdAndCloudProvider(orgId, active, teamId, cloudProvider, pageable);
        }
        if (teamId != null) {
            return findByOrgIdAndStatusAndTeamId(orgId, active, teamId, pageable);
        }
        if (cloudProvider != null) {
            return findByOrgIdAndStatusAndCloudProvider(orgId, active, cloudProvider, pageable);
        }
        return findByOrgIdAndStatus(orgId, active, pageable);
    }

    @Query("""
            select new io.pallet.orgteam.app.AppRef(a.id, a.slug) from App a
            where a.orgId = :orgId and a.status = io.pallet.orgteam.app.AppStatus.ACTIVE
            order by a.createdAt, a.id
            """)
    List<AppRef> findActiveRefs(String orgId);

    @Modifying
    @Query(value = """
            UPDATE org_team.apps
            SET status = 'DELETED', deleted_at = :now, updated_at = :now, version = version + 1
            WHERE org_id = :orgId AND status = 'ACTIVE'
            """, nativeQuery = true)
    void deleteAllActive(String orgId, Instant now);

    @Modifying
    @Query("delete from App a where a.orgId = :orgId")
    int deleteAllForOrg(String orgId);
}
