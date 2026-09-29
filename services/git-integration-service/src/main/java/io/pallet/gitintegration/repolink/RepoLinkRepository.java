package io.pallet.gitintegration.repolink;

import io.pallet.gitintegration.config.CrossTenant;
import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface RepoLinkRepository extends Repository<RepoLink, UUID> {

    @Query("SELECT l FROM RepoLink l WHERE l.orgId = :orgId AND l.appId = :appId")
    Optional<RepoLink> findLink(@Param("orgId") String orgId, @Param("appId") UUID appId);

    /**
     * Every link a push to {@code branch} should build, across every org, in {@code app_id} order so that heads are
     * always locked in the same order. Served by {@code ix_repo_links_repo_active}.
     */
    @CrossTenant("push fan-out: a repository is keyed by GitHub, and each org's link is handled with its own org id"
            + " from here on")
    @Query(value = """
            SELECT l.* FROM git_integration.repo_links l
              JOIN git_integration.apps a ON a.app_id = l.app_id AND a.org_id = l.org_id
             WHERE l.repo_id = :repoId AND l.status = 'ACTIVE' AND l.installation_id = :installationId
               AND l.production_branch = :branch AND l.auto_deploy AND a.status = 'ACTIVE'
             ORDER BY l.app_id
            """, nativeQuery = true)
    List<RepoLink> findPushTargets(
            @Param("installationId") long installationId, @Param("repoId") long repoId, @Param("branch") String branch);

    /**
     * The link as the head reconciler may act on it: active, auto-deploying, on an active app and an active
     * installation. Read again inside each reconciler transaction, since the plan was made outside it.
     */
    @Query(value = """
            SELECT l.* FROM git_integration.repo_links l
              JOIN git_integration.apps a ON a.app_id = l.app_id AND a.org_id = l.org_id
              JOIN git_integration.installations i ON i.installation_id = l.installation_id
             WHERE l.org_id = :orgId AND l.app_id = :appId AND l.status = 'ACTIVE' AND l.auto_deploy
               AND a.status = 'ACTIVE' AND i.status = 'ACTIVE'
            """, nativeQuery = true)
    Optional<RepoLink> findReconcileTarget(@Param("orgId") String orgId, @Param("appId") UUID appId);

    /**
     * Every link the head reconciler checks, installation by installation, starting with the installations after
     * {@code afterInstallation} (or at it, when {@code resume}) and wrapping around to the rest. Read inside a
     * transaction and closed by the caller.
     */
    @CrossTenant("the head reconciler walks every installation; each link is acted on under its own org id")
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "500"))
    @Query(value = """
            SELECT l.app_id AS "appId", l.org_id AS "orgId", l.installation_id AS "installationId",
                   l.repo_id AS "repoId", l.production_branch AS "branch"
              FROM git_integration.repo_links l
              JOIN git_integration.apps a ON a.app_id = l.app_id AND a.org_id = l.org_id
              JOIN git_integration.installations i ON i.installation_id = l.installation_id
             WHERE l.status = 'ACTIVE' AND l.auto_deploy AND a.status = 'ACTIVE' AND i.status = 'ACTIVE'
             ORDER BY CASE WHEN l.installation_id > :afterInstallation
                             OR (:resume AND l.installation_id = :afterInstallation) THEN 0 ELSE 1 END,
                      l.installation_id, l.org_id, l.app_id
            """, nativeQuery = true)
    Stream<ReconcileCandidate> streamReconcileCandidates(
            @Param("afterInstallation") long afterInstallation, @Param("resume") boolean resume);

    interface ReconcileCandidate {

        UUID getAppId();

        String getOrgId();

        long getInstallationId();

        long getRepoId();

        String getBranch();
    }

    /**
     * Every active link on an active installation that was last checked at least {@code minAgeSeconds} ago, in turns:
     * each org's least recently checked link on each installation first, then each one's second, and so on, oldest
     * first within a turn. An org with hundreds of links can't push another org's few to the back. Read inside a
     * transaction and closed by the caller.
     */
    @CrossTenant("access re-verification walks every installation; each link is acted on under its own org id")
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "500"))
    @Query(value = """
            SELECT app_id AS "appId", org_id AS "orgId", installation_id AS "installationId", repo_id AS "repoId",
                   verified_github_user_id AS "githubUserId", verified_github_login AS "githubLogin",
                   version AS "version"
              FROM (SELECT l.app_id, l.org_id, l.installation_id, l.repo_id, l.verified_github_user_id,
                           l.verified_github_login, l.version, l.access_checked_at,
                           row_number() OVER (PARTITION BY l.installation_id, l.org_id
                                              ORDER BY l.access_checked_at, l.app_id) AS turn
                      FROM git_integration.repo_links l
                      JOIN git_integration.installations i ON i.installation_id = l.installation_id
                     WHERE l.status = 'ACTIVE' AND i.status = 'ACTIVE'
                       AND l.access_checked_at <= now() - make_interval(secs => :minAgeSeconds)) due
             ORDER BY turn, access_checked_at, app_id
            """, nativeQuery = true)
    Stream<ReverifyCandidate> streamReverifyCandidates(@Param("minAgeSeconds") double minAgeSeconds);

    interface ReverifyCandidate {

        UUID getAppId();

        String getOrgId();

        long getInstallationId();

        long getRepoId();

        long getGithubUserId();

        String getGithubLogin();

        long getVersion();
    }

    /**
     * Records that the verifier still has access, only if the link is still active under the same verifier and
     * version it was checked against. Bookkeeping, not a change to the link, so the version stays.
     *
     * @return 1 if recorded, 0 if the link changed since it was checked
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.repo_links
               SET access_checked_at = now(), verified_permission = :permission, verified_github_login = :githubLogin
             WHERE org_id = :orgId AND app_id = :appId AND status = 'ACTIVE'
               AND verified_github_user_id = :githubUserId AND version = :version
            """, nativeQuery = true)
    int confirmAccess(
            @Param("orgId") String orgId,
            @Param("appId") UUID appId,
            @Param("githubUserId") long githubUserId,
            @Param("version") long version,
            @Param("permission") String permission,
            @Param("githubLogin") String githubLogin);

    /** Seconds since the least recently checked link was checked, among those the re-verifier would take; 0 if none. */
    @CrossTenant("an operational gauge over every org's links, exposing no org's data")
    @Query(value = """
            SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(l.access_checked_at)), 0)::float8
              FROM git_integration.repo_links l
              JOIN git_integration.installations i ON i.installation_id = l.installation_id
             WHERE l.status = 'ACTIVE' AND i.status = 'ACTIVE'
            """, nativeQuery = true)
    double oldestAccessCheckAgeSeconds();

    boolean existsByOrgIdAndAppIdAndStatus(String orgId, UUID appId, RepoLink.Status status);

    /**
     * Creates the link, or re-creates it over a {@code DISCONNECTED} row. An {@code ACTIVE} row is left untouched; a
     * concurrent insert for the same app waits on the primary key and then finds that row, so the loser sees 0 here
     * rather than a constraint violation.
     *
     * @return 1 if the link is now this one, 0 if the app already had an active link
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO git_integration.repo_links
                (app_id, org_id, installation_id, repo_id, repo_full_name, production_branch, root_directory,
                 auto_deploy, status, verified_by_user_id, verified_github_user_id, verified_github_login,
                 verified_permission, access_verified_at, access_checked_at)
            VALUES (:appId, :orgId, :installationId, :repoId, :repoFullName, :productionBranch, :rootDirectory,
                    :autoDeploy, 'ACTIVE', :userId, :githubUserId, :githubLogin, :permission, now(), now())
            ON CONFLICT (app_id) DO UPDATE
               SET installation_id = EXCLUDED.installation_id, repo_id = EXCLUDED.repo_id,
                   repo_full_name = EXCLUDED.repo_full_name, production_branch = EXCLUDED.production_branch,
                   root_directory = EXCLUDED.root_directory, auto_deploy = EXCLUDED.auto_deploy, status = 'ACTIVE',
                   disconnect_reason = NULL, disconnected_at = NULL,
                   verified_by_user_id = EXCLUDED.verified_by_user_id,
                   verified_github_user_id = EXCLUDED.verified_github_user_id,
                   verified_github_login = EXCLUDED.verified_github_login,
                   verified_permission = EXCLUDED.verified_permission, access_verified_at = now(),
                   access_checked_at = now(), updated_at = now(),
                   version = git_integration.repo_links.version + 1
             WHERE git_integration.repo_links.status = 'DISCONNECTED'
               AND git_integration.repo_links.org_id = EXCLUDED.org_id
            """, nativeQuery = true)
    int upsertActive(
            @Param("orgId") String orgId,
            @Param("appId") UUID appId,
            @Param("installationId") long installationId,
            @Param("repoId") long repoId,
            @Param("repoFullName") String repoFullName,
            @Param("productionBranch") String productionBranch,
            @Param("rootDirectory") String rootDirectory,
            @Param("autoDeploy") boolean autoDeploy,
            @Param("userId") String userId,
            @Param("githubUserId") long githubUserId,
            @Param("githubLogin") String githubLogin,
            @Param("permission") String permission);

    /** @return the active link, with its row locked until the transaction ends */
    @Query(value = """
            SELECT * FROM git_integration.repo_links
             WHERE org_id = :orgId AND app_id = :appId AND status = 'ACTIVE'
               FOR UPDATE
            """, nativeQuery = true)
    Optional<RepoLink> lockActive(@Param("orgId") String orgId, @Param("appId") UUID appId);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.repo_links
               SET production_branch = :productionBranch, root_directory = :rootDirectory,
                   auto_deploy = :autoDeploy, updated_at = now(), version = version + 1
             WHERE org_id = :orgId AND app_id = :appId AND status = 'ACTIVE'
            """, nativeQuery = true)
    void updateSettings(
            @Param("orgId") String orgId,
            @Param("appId") UUID appId,
            @Param("productionBranch") String productionBranch,
            @Param("rootDirectory") String rootDirectory,
            @Param("autoDeploy") boolean autoDeploy);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.repo_links
               SET verified_by_user_id = :userId, verified_github_user_id = :githubUserId,
                   verified_github_login = :githubLogin, verified_permission = :permission,
                   repo_full_name = :repoFullName, access_verified_at = now(), access_checked_at = now(),
                   updated_at = now(), version = version + 1
             WHERE org_id = :orgId AND app_id = :appId AND status = 'ACTIVE'
            """, nativeQuery = true)
    void updateVerifier(
            @Param("orgId") String orgId,
            @Param("appId") UUID appId,
            @Param("userId") String userId,
            @Param("githubUserId") long githubUserId,
            @Param("githubLogin") String githubLogin,
            @Param("permission") String permission,
            @Param("repoFullName") String repoFullName);

    /** @return 1 if the active link was disconnected, 0 if the app had none */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.repo_links
               SET status = 'DISCONNECTED', disconnect_reason = :reason, disconnected_at = now(),
                   updated_at = now(), version = version + 1
             WHERE org_id = :orgId AND app_id = :appId AND status = 'ACTIVE'
            """, nativeQuery = true)
    int disconnect(@Param("orgId") String orgId, @Param("appId") UUID appId, @Param("reason") String reason);

    /** @return the org's active links on the installation, locked in {@code app_id} order */
    @Query(value = """
            SELECT * FROM git_integration.repo_links
             WHERE org_id = :orgId AND installation_id = :installationId AND status = 'ACTIVE'
             ORDER BY app_id
               FOR UPDATE
            """, nativeQuery = true)
    List<RepoLink> lockActiveOnInstallationOf(
            @Param("orgId") String orgId, @Param("installationId") long installationId);

    /** @return every active link of the org, locked in {@code app_id} order */
    @Query(value = """
            SELECT * FROM git_integration.repo_links
             WHERE org_id = :orgId AND status = 'ACTIVE'
             ORDER BY app_id
               FOR UPDATE
            """, nativeQuery = true)
    List<RepoLink> lockAllActive(@Param("orgId") String orgId);

    /** @return every org's active links on the installation, locked in {@code app_id} order */
    @CrossTenant(
            "installation lifecycle: GitHub's event names an installation, and every org's links on it end with it")
    @Query(value = """
            SELECT * FROM git_integration.repo_links
             WHERE installation_id = :installationId AND status = 'ACTIVE'
             ORDER BY app_id
               FOR UPDATE
            """, nativeQuery = true)
    List<RepoLink> lockActiveOnInstallation(@Param("installationId") long installationId);

    /** @return every org's active links to the repositories through the installation, locked in {@code app_id} order */
    @CrossTenant("repository lifecycle: GitHub's event names a repository, and every org that linked it loses it")
    @Query(value = """
            SELECT * FROM git_integration.repo_links
             WHERE installation_id = :installationId AND repo_id IN (:repoIds) AND status = 'ACTIVE'
             ORDER BY app_id
               FOR UPDATE
            """, nativeQuery = true)
    List<RepoLink> lockActiveOnRepositories(
            @Param("installationId") long installationId, @Param("repoIds") Collection<Long> repoIds);

    @CrossTenant("lifecycle fan-out: a suspension notice names each linked org's affected apps")
    @Query(value = """
            SELECT * FROM git_integration.repo_links
             WHERE installation_id = :installationId AND status = 'ACTIVE'
             ORDER BY app_id
            """, nativeQuery = true)
    List<RepoLink> findActiveOnInstallation(@Param("installationId") long installationId);

    @CrossTenant("the periodic sync compares the repositories every org links through an installation with GitHub's")
    @Query(value = """
            SELECT DISTINCT repo_id FROM git_integration.repo_links
             WHERE installation_id = :installationId AND status = 'ACTIVE'
            """, nativeQuery = true)
    List<Long> findActiveRepoIds(@Param("installationId") long installationId);

    /** Call only with every listed link locked by the caller, which selected them under their own orgs' rules. */
    @CrossTenant("disconnects links the teardown already locked, across the orgs one GitHub event affected")
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.repo_links
               SET status = 'DISCONNECTED', disconnect_reason = :reason, disconnected_at = now(),
                   updated_at = now(), version = version + 1
             WHERE app_id IN (:appIds) AND status = 'ACTIVE'
            """, nativeQuery = true)
    int disconnectAll(@Param("appIds") Collection<UUID> appIds, @Param("reason") String reason);

    /** A repository's name is display only; its id never changes, so a rename breaks nothing that is linked. */
    @CrossTenant("a repository's name belongs to GitHub and is the same for every org that links it")
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.repo_links
               SET repo_full_name = :fullName, updated_at = now(), version = version + 1
             WHERE repo_id = :repoId AND repo_full_name <> :fullName
            """, nativeQuery = true)
    int renameRepository(@Param("repoId") long repoId, @Param("fullName") String fullName);
}
