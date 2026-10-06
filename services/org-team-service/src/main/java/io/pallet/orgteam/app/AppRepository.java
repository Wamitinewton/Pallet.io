package io.pallet.orgteam.app;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AppRepository extends JpaRepository<App, UUID>, JpaSpecificationExecutor<App> {

    Optional<App> findByOrgIdAndIdAndStatus(String orgId, UUID id, AppStatus status);

    boolean existsByOrgIdAndSlugAndStatus(String orgId, String slug, AppStatus status);

    long countByOrgIdAndStatus(String orgId, AppStatus status);

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
