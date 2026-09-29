package io.pallet.gitintegration.push;

import io.pallet.gitintegration.config.CrossTenant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** {@code branch_heads} has no {@code org_id} of its own; every method reaches it through the org's repo link. */
public interface BranchHeadRepository extends Repository<BranchHead, BranchHead.Key> {

    @Query(value = """
            SELECT b.* FROM git_integration.branch_heads b
              JOIN git_integration.repo_links r ON r.app_id = b.app_id
             WHERE r.org_id = :orgId AND b.app_id = :appId AND b.branch = :branch
            """, nativeQuery = true)
    Optional<BranchHead> findHead(
            @Param("orgId") String orgId, @Param("appId") UUID appId, @Param("branch") String branch);

    @CrossTenant("the head reconciler reads, in one query, the heads of every org's link to one repository branch")
    @Query(value = """
            SELECT etag FROM git_integration.branch_heads
             WHERE app_id IN (:appIds) AND branch = :branch
            """, nativeQuery = true)
    List<String> findEtags(@Param("appIds") Collection<UUID> appIds, @Param("branch") String branch);

    /** @return the head's SHA, with its row locked until the transaction ends; empty when the branch has no head */
    @Query(value = """
            SELECT b.head_sha FROM git_integration.branch_heads b
              JOIN git_integration.repo_links r ON r.app_id = b.app_id
             WHERE r.org_id = :orgId AND b.app_id = :appId AND b.branch = :branch
               FOR UPDATE OF b
            """, nativeQuery = true)
    Optional<String> lockForUpdate(
            @Param("orgId") String orgId, @Param("appId") UUID appId, @Param("branch") String branch);

    /**
     * Moves the head to {@code headSha}, creating it for a branch that has none. Call only with the head, or for a
     * missing head the repo link, locked, after the chain rule accepted {@code headSha}.
     *
     * @param etag the {@code ETag} of the branch read that returned {@code headSha}, or null; a row's {@code etag}
     *     always describes its own {@code head_sha}
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO git_integration.branch_heads (app_id, branch, head_sha, advanced_at, etag)
            SELECT r.app_id, :branch, :headSha, now(), :etag FROM git_integration.repo_links r
             WHERE r.org_id = :orgId AND r.app_id = :appId
            ON CONFLICT (app_id, branch) DO UPDATE
               SET head_sha = EXCLUDED.head_sha, advanced_at = EXCLUDED.advanced_at, etag = EXCLUDED.etag
            """, nativeQuery = true)
    int advance(
            @Param("orgId") String orgId,
            @Param("appId") UUID appId,
            @Param("branch") String branch,
            @Param("headSha") String headSha,
            @Param("etag") String etag);

    /**
     * Takes GitHub's head as the branch's first, publishing nothing. Loses to a head a push created meanwhile.
     *
     * @return 1 if adopted, 0 if the branch already had a head
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO git_integration.branch_heads (app_id, branch, head_sha, advanced_at, etag)
            SELECT r.app_id, :branch, :headSha, now(), :etag FROM git_integration.repo_links r
             WHERE r.org_id = :orgId AND r.app_id = :appId
            ON CONFLICT (app_id, branch) DO NOTHING
            """, nativeQuery = true)
    int adopt(
            @Param("orgId") String orgId,
            @Param("appId") UUID appId,
            @Param("branch") String branch,
            @Param("headSha") String headSha,
            @Param("etag") String etag);

    /** Stores the {@code ETag} of a read that found the head still at {@code headSha}; any other head keeps its own. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.branch_heads b SET etag = :etag
              FROM git_integration.repo_links r
             WHERE r.app_id = b.app_id AND r.org_id = :orgId AND b.app_id = :appId AND b.branch = :branch
               AND b.head_sha = :headSha
            """, nativeQuery = true)
    int recordEtag(
            @Param("orgId") String orgId,
            @Param("appId") UUID appId,
            @Param("branch") String branch,
            @Param("headSha") String headSha,
            @Param("etag") String etag);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            DELETE FROM git_integration.branch_heads b
             USING git_integration.repo_links r
             WHERE r.app_id = b.app_id AND r.org_id = :orgId AND b.app_id = :appId
            """, nativeQuery = true)
    void deleteAll(@Param("orgId") String orgId, @Param("appId") UUID appId);

    /** The next push to {@code branch} then has no head to chain from, and is accepted as the first. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            DELETE FROM git_integration.branch_heads b
             USING git_integration.repo_links r
             WHERE r.app_id = b.app_id AND r.org_id = :orgId AND b.app_id = :appId AND b.branch = :branch
            """, nativeQuery = true)
    void deleteBranch(@Param("orgId") String orgId, @Param("appId") UUID appId, @Param("branch") String branch);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO git_integration.branch_heads (app_id, branch, head_sha, advanced_at)
            SELECT r.app_id, :branch, :headSha, now() FROM git_integration.repo_links r
             WHERE r.org_id = :orgId AND r.app_id = :appId
            """, nativeQuery = true)
    void insert(
            @Param("orgId") String orgId,
            @Param("appId") UUID appId,
            @Param("branch") String branch,
            @Param("headSha") String headSha);

    @CrossTenant("teardown of repo links it already holds locked, across the orgs one GitHub event affected")
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM git_integration.branch_heads WHERE app_id IN (:appIds)", nativeQuery = true)
    void deleteAllOf(@Param("appIds") Collection<UUID> appIds);
}
