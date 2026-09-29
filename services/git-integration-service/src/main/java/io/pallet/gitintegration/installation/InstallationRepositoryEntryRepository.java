package io.pallet.gitintegration.installation;

import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The installation's repository read model. A sync stamps every row it sees with the transaction's {@code now()}, so
 * "absent from this listing" is "stamped before this transaction".
 */
public interface InstallationRepositoryEntryRepository
        extends JpaRepository<InstallationRepositoryEntry, InstallationRepositoryEntry.Key> {

    @Modifying
    @Query(value = """
            INSERT INTO git_integration.installation_repositories
                (installation_id, repo_id, full_name, default_branch, is_private, archived, synced_at)
            VALUES (:installationId, :repoId, :fullName, :defaultBranch, :isPrivate, :archived, now())
            ON CONFLICT (installation_id, repo_id) DO UPDATE
               SET full_name = EXCLUDED.full_name, default_branch = EXCLUDED.default_branch,
                   is_private = EXCLUDED.is_private, archived = EXCLUDED.archived, synced_at = now()
            """, nativeQuery = true)
    void upsert(
            @Param("installationId") long installationId,
            @Param("repoId") long repoId,
            @Param("fullName") String fullName,
            @Param("defaultBranch") String defaultBranch,
            @Param("isPrivate") boolean isPrivate,
            @Param("archived") boolean archived);

    /** Deletes every row this transaction's upserts didn't touch. */
    @Modifying
    @Query(value = """
            DELETE FROM git_integration.installation_repositories
             WHERE installation_id = :installationId AND synced_at < now()
            """, nativeQuery = true)
    void deleteNotSyncedInThisTransaction(@Param("installationId") long installationId);

    /**
     * {@code installation_repositories.added}: the payload names no default branch, so a new row has none until the
     * next sync; an existing row keeps its own.
     */
    @Modifying
    @Query(value = """
            INSERT INTO git_integration.installation_repositories
                (installation_id, repo_id, full_name, default_branch, is_private, archived, synced_at)
            VALUES (:installationId, :repoId, :fullName, NULL, :isPrivate, false, now())
            ON CONFLICT (installation_id, repo_id) DO UPDATE
               SET full_name = EXCLUDED.full_name, is_private = EXCLUDED.is_private
            """, nativeQuery = true)
    void upsertAdded(
            @Param("installationId") long installationId,
            @Param("repoId") long repoId,
            @Param("fullName") String fullName,
            @Param("isPrivate") boolean isPrivate);

    @Modifying
    @Query(value = """
            DELETE FROM git_integration.installation_repositories
             WHERE installation_id = :installationId AND repo_id IN (:repoIds)
            """, nativeQuery = true)
    void deleteRepositories(@Param("installationId") long installationId, @Param("repoIds") Collection<Long> repoIds);

    /** A repository id is GitHub's and never reused, so every installation's row for it takes the new name. */
    @Modifying
    @Query(value = """
            UPDATE git_integration.installation_repositories SET full_name = :fullName
             WHERE repo_id = :repoId AND full_name <> :fullName
            """, nativeQuery = true)
    void rename(@Param("repoId") long repoId, @Param("fullName") String fullName);

    @Modifying
    @Query(value = """
            UPDATE git_integration.installation_repositories SET archived = :archived
             WHERE installation_id = :installationId AND repo_id = :repoId
            """, nativeQuery = true)
    void setArchived(
            @Param("installationId") long installationId,
            @Param("repoId") long repoId,
            @Param("archived") boolean archived);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM git_integration.installation_repositories
                            WHERE installation_id = :installationId AND repo_id = :repoId AND archived)
            """, nativeQuery = true)
    boolean isArchived(@Param("installationId") long installationId, @Param("repoId") long repoId);
}
