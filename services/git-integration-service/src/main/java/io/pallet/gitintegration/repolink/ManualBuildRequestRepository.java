package io.pallet.gitintegration.repolink;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The window is measured on the database clock, the same one that stamps {@code created_at}. */
public interface ManualBuildRequestRepository extends Repository<ManualBuildRequest, ManualBuildRequest.Key> {

    /** {@code retryAfterSeconds} is null when the window is empty. */
    interface RecentBuilds {

        long getRequests();

        Long getRetryAfterSeconds();
    }

    @Query("""
            SELECT r FROM ManualBuildRequest r
             WHERE r.orgId = :orgId AND r.key.appId = :appId AND r.key.idempotencyKey = :idempotencyKey
            """)
    Optional<ManualBuildRequest> findRequest(
            @Param("orgId") String orgId, @Param("appId") UUID appId, @Param("idempotencyKey") String idempotencyKey);

    /** Served by {@code ix_manual_build_requests_app_recent}. */
    @Query(value = """
            SELECT count(*) AS "requests",
                   CAST(ceil(extract(epoch FROM min(created_at) + interval '1 hour' - now())) AS bigint)
                       AS "retryAfterSeconds"
              FROM git_integration.manual_build_requests
             WHERE org_id = :orgId AND app_id = :appId AND created_at > now() - interval '1 hour'
            """, nativeQuery = true)
    RecentBuilds lastHour(@Param("orgId") String orgId, @Param("appId") UUID appId);

    /** @return 0 when a request with this key already exists, including one committed while this insert waited */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO git_integration.manual_build_requests
                (app_id, idempotency_key, org_id, request_hash, event_id, branch, commit_sha, requested_by)
            VALUES (:appId, :idempotencyKey, :orgId, :requestHash, :eventId, :branch, :commitSha, :requestedBy)
            ON CONFLICT (app_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insert(
            @Param("orgId") String orgId,
            @Param("appId") UUID appId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("eventId") UUID eventId,
            @Param("branch") String branch,
            @Param("commitSha") String commitSha,
            @Param("requestedBy") String requestedBy);
}
