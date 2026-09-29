package io.pallet.gitintegration.delivery;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every processor query locks with {@code SKIP LOCKED}: a row another worker holds is being processed, so waiting for
 * it would only duplicate that work. Every {@code next_attempt_at} is computed from the database's {@code now()}.
 */
public interface DeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    String CLAIM_NEXT = """
            SELECT * FROM git_integration.webhook_deliveries
            WHERE status = 'RECEIVED' AND next_attempt_at <= now() AND delivery_id NOT IN (:excluded)
            ORDER BY next_attempt_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;

    /** @return 1 if inserted, 0 if a delivery with this id is already stored */
    @Modifying
    @Query(value = """
            INSERT INTO git_integration.webhook_deliveries
                (delivery_id, event, action, installation_id, payload, status, outcome_reason, traceparent)
            VALUES (:deliveryId, :event, :action, :installationId, CAST(:payload AS jsonb), :status, :outcomeReason,
                    :traceparent)
            ON CONFLICT (delivery_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("deliveryId") UUID deliveryId,
            @Param("event") String event,
            @Param("action") String action,
            @Param("installationId") Long installationId,
            @Param("payload") String payload,
            @Param("status") String status,
            @Param("outcomeReason") String outcomeReason,
            @Param("traceparent") String traceparent);

    /** Which of {@code ids} are stored, whatever their status; {@code ids} must not be empty. */
    @Query(
            value = "SELECT delivery_id FROM git_integration.webhook_deliveries WHERE delivery_id IN (:ids)",
            nativeQuery = true)
    List<UUID> findStoredIds(@Param("ids") Collection<UUID> ids);

    /** The oldest due delivery no other worker holds; {@code excluded} must not be empty. */
    @Query(value = CLAIM_NEXT, nativeQuery = true)
    Optional<WebhookDelivery> claimNext(@Param("excluded") Collection<UUID> excluded);

    /** Re-claims one delivery after a GitHub lookup, whatever its {@code next_attempt_at}. */
    @Query(value = """
            SELECT * FROM git_integration.webhook_deliveries
            WHERE delivery_id = :id AND status = 'RECEIVED'
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<WebhookDelivery> claim(@Param("id") UUID id);

    /** Locks a delivery to record why its last round failed; empty if it is done or another worker holds it. */
    @Query(value = """
            SELECT attempts, unavailable_streak AS "unavailableStreak"
            FROM git_integration.webhook_deliveries
            WHERE delivery_id = :id AND status = 'RECEIVED'
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<RetryState> lockForRetry(@Param("id") UUID id);

    /** @return 1 if leased, 0 if the delivery is done or another worker holds it */
    @Modifying
    @Query(value = """
            UPDATE git_integration.webhook_deliveries
            SET next_attempt_at = now() + make_interval(secs => :seconds)
            WHERE delivery_id = (
                SELECT delivery_id FROM git_integration.webhook_deliveries
                WHERE delivery_id = :id AND status = 'RECEIVED'
                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int lease(@Param("id") UUID id, @Param("seconds") double seconds);

    @Modifying
    @Query(value = """
            UPDATE git_integration.webhook_deliveries
            SET status = 'PROCESSED', outcome_reason = NULL, unavailable_streak = 0, processed_at = now()
            WHERE delivery_id = :id
            """, nativeQuery = true)
    void markProcessed(@Param("id") UUID id);

    @Modifying
    @Query(value = """
            UPDATE git_integration.webhook_deliveries
            SET status = 'IGNORED', outcome_reason = :reason, unavailable_streak = 0, processed_at = now()
            WHERE delivery_id = :id
            """, nativeQuery = true)
    void markIgnored(@Param("id") UUID id, @Param("reason") String reason);

    @Modifying
    @Query(value = """
            UPDATE git_integration.webhook_deliveries
            SET attempts = attempts + 1, unavailable_streak = 0, last_error = :error,
                next_attempt_at = now() + make_interval(secs => :delaySeconds)
            WHERE delivery_id = :id
            """, nativeQuery = true)
    void retryLater(@Param("id") UUID id, @Param("error") String error, @Param("delaySeconds") double delaySeconds);

    @Modifying
    @Query(value = """
            UPDATE git_integration.webhook_deliveries
            SET status = 'PARKED', attempts = attempts + 1, unavailable_streak = 0, last_error = :error
            WHERE delivery_id = :id
            """, nativeQuery = true)
    void park(@Param("id") UUID id, @Param("error") String error);

    /** GitHub's rate limit: wait for its reset without spending an attempt. */
    @Modifying
    @Query(value = """
            UPDATE git_integration.webhook_deliveries
            SET last_error = :error,
                next_attempt_at = greatest(CAST(:resetAt AS timestamptz), now()) + make_interval(secs => :jitterSeconds)
            WHERE delivery_id = :id
            """, nativeQuery = true)
    void waitForRateLimit(
            @Param("id") UUID id,
            @Param("error") String error,
            @Param("resetAt") Instant resetAt,
            @Param("jitterSeconds") double jitterSeconds);

    /** GitHub unavailable: back off by the unavailability streak without spending an attempt. */
    @Modifying
    @Query(value = """
            UPDATE git_integration.webhook_deliveries
            SET unavailable_streak = unavailable_streak + 1, last_error = :error,
                next_attempt_at = now() + make_interval(secs => :delaySeconds)
            WHERE delivery_id = :id
            """, nativeQuery = true)
    void waitForGitHub(@Param("id") UUID id, @Param("error") String error, @Param("delaySeconds") double delaySeconds);

    /** One statement over the two partial indexes, so the gauges never scan the retained history. */
    @Query(value = """
            SELECT (SELECT count(*) FROM git_integration.webhook_deliveries WHERE status = 'RECEIVED') AS pending,
                   (SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(received_at)), 0)
                    FROM git_integration.webhook_deliveries WHERE status = 'RECEIVED') AS "oldestPendingAgeSeconds",
                   (SELECT count(*) FROM git_integration.webhook_deliveries WHERE status = 'PARKED') AS parked
            """, nativeQuery = true)
    DeliveryStats stats();

    interface RetryState {

        int getAttempts();

        int getUnavailableStreak();
    }

    interface DeliveryStats {

        Number getPending();

        Number getOldestPendingAgeSeconds();

        Number getParked();
    }
}
