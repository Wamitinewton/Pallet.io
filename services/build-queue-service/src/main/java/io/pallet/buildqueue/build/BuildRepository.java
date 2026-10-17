package io.pallet.buildqueue.build;

import io.pallet.buildqueue.config.CrossTenant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Reads of {@code builds}. Writes go through {@link BuildTransitions}. Queries over waiting builds spell out
 * {@code status = 'QUEUED'} so the planner can match the partial indexes.
 */
public interface BuildRepository extends Repository<Build, UUID> {

    Optional<Build> findByOrgIdAndBuildId(String orgId, UUID buildId);

    boolean existsByOrgIdAndBuildId(String orgId, UUID buildId);

    /** The app and branch's waiting build, row-locked until the caller's transaction ends. Call after the org lock. */
    @Query(value = """
            SELECT * FROM build_queue.builds
             WHERE org_id = :orgId AND app_id = :appId AND branch = :branch AND status = 'QUEUED'
               FOR UPDATE
            """, nativeQuery = true)
    Optional<Build> lockQueued(
            @Param("orgId") String orgId, @Param("appId") String appId, @Param("branch") String branch);

    @Query(value = """
            SELECT count(*) FROM build_queue.builds
             WHERE org_id = :orgId AND status = 'QUEUED'
            """, nativeQuery = true)
    long countQueued(@Param("orgId") String orgId);

    @CrossTenant("tells a build-service report misrouted to the wrong org apart from one for a build never queued here")
    @Query(value = "SELECT org_id FROM build_queue.builds WHERE build_id = :buildId", nativeQuery = true)
    Optional<String> findOwningOrgId(@Param("buildId") UUID buildId);

    /** The org's in-flight builds: running before dispatched, each oldest dispatch first. */
    @Query(value = """
            SELECT * FROM build_queue.builds
             WHERE org_id = :orgId AND status IN ('DISPATCHED', 'RUNNING')
             ORDER BY CASE status WHEN 'RUNNING' THEN 0 ELSE 1 END, dispatched_at, build_id
            """, nativeQuery = true)
    List<Build> findInFlight(@Param("orgId") String orgId);

    /** A slice of the org's waiting builds in the dispatcher's within-org order. */
    @Query(value = """
            SELECT * FROM build_queue.builds
             WHERE org_id = :orgId AND status = 'QUEUED'
             ORDER BY priority_class, enqueued_at, build_id
             LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Build> findQueued(@Param("orgId") String orgId, @Param("offset") long offset, @Param("limit") int limit);

    Page<Build> findByOrgIdAndAppId(String orgId, String appId, Pageable pageable);

    Page<Build> findByOrgIdAndAppIdAndStatusIn(
            String orgId, String appId, Collection<BuildStatus> statuses, Pageable pageable);

    @CrossTenant("platform-wide capacity spans every org; only ever folded into a waiting reason, never returned")
    @Query(
            value = "SELECT count(*) FROM build_queue.builds WHERE status IN ('DISPATCHED', 'RUNNING')",
            nativeQuery = true)
    long countInFlightOnPlatform();
}
