package io.pallet.gitintegration.installation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * GitHub-keyed, so no method takes an org. Every write that decides whether an installation is in use takes the row
 * lock first, which serializes links and unlinks of one installation across orgs.
 */
public interface InstallationRepository extends JpaRepository<Installation, Long> {

    /**
     * The link path's upsert: GitHub has just confirmed the installation exists and isn't suspended, so the row is
     * {@code ACTIVE} and in use whatever it said before.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO git_integration.installations
                (installation_id, account_id, account_login, account_type, repository_selection, status, permissions)
            VALUES (:installationId, :accountId, :accountLogin, :accountType, :repositorySelection, 'ACTIVE',
                    CAST(:permissions AS jsonb))
            ON CONFLICT (installation_id) DO UPDATE
               SET account_id = EXCLUDED.account_id, account_login = EXCLUDED.account_login,
                   account_type = EXCLUDED.account_type, repository_selection = EXCLUDED.repository_selection,
                   permissions = EXCLUDED.permissions, status = 'ACTIVE', unused_since = NULL,
                   suspended_at = NULL, deleted_at = NULL, updated_at = now(),
                   version = git_integration.installations.version + 1
            """, nativeQuery = true)
    void upsertLinked(
            @Param("installationId") long installationId,
            @Param("accountId") long accountId,
            @Param("accountLogin") String accountLogin,
            @Param("accountType") String accountType,
            @Param("repositorySelection") String repositorySelection,
            @Param("permissions") String permissions);

    /**
     * {@code installation.created}: a new row starts its unused clock (no link can reference a row that didn't exist);
     * an existing one takes the GitHub facts and keeps its status and clock.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO git_integration.installations
                (installation_id, account_id, account_login, account_type, repository_selection, status, permissions,
                 unused_since)
            VALUES (:installationId, :accountId, :accountLogin, :accountType, :repositorySelection, 'ACTIVE',
                    CAST(:permissions AS jsonb), now())
            ON CONFLICT (installation_id) DO UPDATE
               SET account_id = EXCLUDED.account_id, account_login = EXCLUDED.account_login,
                   account_type = EXCLUDED.account_type, repository_selection = EXCLUDED.repository_selection,
                   permissions = EXCLUDED.permissions, updated_at = now(),
                   version = git_integration.installations.version + 1
            """, nativeQuery = true)
    void upsertCreated(
            @Param("installationId") long installationId,
            @Param("accountId") long accountId,
            @Param("accountLogin") String accountLogin,
            @Param("accountType") String accountType,
            @Param("repositorySelection") String repositorySelection,
            @Param("permissions") String permissions);

    /** @return the installation's status, with its row locked until the transaction ends */
    @Query(value = """
            SELECT status FROM git_integration.installations WHERE installation_id = :installationId FOR UPDATE
            """, nativeQuery = true)
    Optional<String> lockStatus(@Param("installationId") long installationId);

    /**
     * Starts the unused clock once no org links the installation. Call with the installation row locked, so a
     * concurrent unlink by another org can't leave both transactions seeing the other's link.
     *
     * @return 1 if the clock started, 0 if an org still links it or it was already running
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.installations i
               SET unused_since = now(), updated_at = now(), version = i.version + 1
             WHERE i.installation_id = :installationId AND i.status <> 'DELETED' AND i.unused_since IS NULL
               AND NOT EXISTS (SELECT 1 FROM git_integration.installation_links l
                                WHERE l.installation_id = i.installation_id AND l.status = 'ACTIVE')
            """, nativeQuery = true)
    int markUnusedIfNoActiveLink(@Param("installationId") long installationId);

    /**
     * A lifecycle webhook for an installation this service never saw: records it from the payload as a new, unused
     * row. A known row, in any state, is left exactly as it is.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO git_integration.installations
                (installation_id, account_id, account_login, account_type, repository_selection, status, permissions,
                 unused_since)
            VALUES (:installationId, :accountId, :accountLogin, :accountType, :repositorySelection, 'ACTIVE',
                    CAST(:permissions AS jsonb), now())
            ON CONFLICT (installation_id) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(
            @Param("installationId") long installationId,
            @Param("accountId") long accountId,
            @Param("accountLogin") String accountLogin,
            @Param("accountType") String accountType,
            @Param("repositorySelection") String repositorySelection,
            @Param("permissions") String permissions);

    /** Locks every listed installation that exists, in id order, the order every multi-installation lock takes. */
    @Query(value = """
            SELECT installation_id FROM git_integration.installations
             WHERE installation_id IN (:installationIds)
             ORDER BY installation_id
               FOR UPDATE
            """, nativeQuery = true)
    List<Long> lockAll(@Param("installationIds") Collection<Long> installationIds);

    /** @return 1 if the installation became {@code DELETED}, 0 if it already was */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.installations
               SET status = 'DELETED', deleted_at = now(), unused_since = NULL, updated_at = now(),
                   version = version + 1
             WHERE installation_id = :installationId AND status <> 'DELETED'
            """, nativeQuery = true)
    int markDeleted(@Param("installationId") long installationId);

    /** @return 1 if an {@code ACTIVE} installation became {@code SUSPENDED} */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.installations
               SET status = 'SUSPENDED', suspended_at = now(), updated_at = now(), version = version + 1
             WHERE installation_id = :installationId AND status = 'ACTIVE'
            """, nativeQuery = true)
    int markSuspended(@Param("installationId") long installationId);

    /** @return 1 if a {@code SUSPENDED} installation became {@code ACTIVE} */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.installations
               SET status = 'ACTIVE', suspended_at = NULL, updated_at = now(), version = version + 1
             WHERE installation_id = :installationId AND status = 'SUSPENDED'
            """, nativeQuery = true)
    int markUnsuspended(@Param("installationId") long installationId);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.installations
               SET permissions = CAST(:permissions AS jsonb), updated_at = now(), version = version + 1
             WHERE installation_id = :installationId AND status <> 'DELETED'
            """, nativeQuery = true)
    int updatePermissions(@Param("installationId") long installationId, @Param("permissions") String permissions);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.installations SET repositories_synced_at = now()
             WHERE installation_id = :installationId
            """, nativeQuery = true)
    void markRepositoriesSynced(@Param("installationId") long installationId);

    /** Installations no org has linked for at least {@code graceSeconds}, longest unused first. */
    @Query(value = """
            SELECT * FROM git_integration.installations
             WHERE status IN ('ACTIVE', 'SUSPENDED')
               AND unused_since <= now() - make_interval(secs => :graceSeconds)
             ORDER BY unused_since, installation_id
             LIMIT :limit
            """, nativeQuery = true)
    List<Installation> findUnusedFor(@Param("graceSeconds") double graceSeconds, @Param("limit") int limit);

    @Query(value = """
            SELECT count(*) FROM git_integration.installations WHERE unused_since IS NOT NULL AND status = 'ACTIVE'
            """, nativeQuery = true)
    long countUnused();

    /** {@code ACTIVE} installations, the one whose repositories were synced longest ago (or never) first. */
    @Query(value = """
            SELECT installation_id FROM git_integration.installations
             WHERE status = 'ACTIVE'
             ORDER BY repositories_synced_at NULLS FIRST, installation_id
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findDueForSync(@Param("limit") int limit);
}
