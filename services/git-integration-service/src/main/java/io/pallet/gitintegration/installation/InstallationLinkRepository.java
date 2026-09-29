package io.pallet.gitintegration.installation;

import io.pallet.gitintegration.config.CrossTenant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The tenant half of an installation. Every method is scoped to one org except the lifecycle fan-out, which is how a
 * GitHub event about one installation reaches each org that linked it.
 */
public interface InstallationLinkRepository extends Repository<InstallationLink, InstallationLink.Key> {

    /**
     * Links the installation to the org, or relinks an {@code UNLINKED} row. An {@code ACTIVE} row is left exactly as
     * it is, so a retried request neither changes who linked it nor counts as a second link.
     *
     * @return 1 if the link was created or reactivated, 0 if it was already {@code ACTIVE}
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO git_integration.installation_links
                (installation_id, org_id, status, linked_by_user_id, linked_by_github_user_id)
            VALUES (:installationId, :orgId, 'ACTIVE', :userId, :githubUserId)
            ON CONFLICT (installation_id, org_id) DO UPDATE
               SET status = 'ACTIVE', linked_by_user_id = EXCLUDED.linked_by_user_id,
                   linked_by_github_user_id = EXCLUDED.linked_by_github_user_id, linked_at = now(),
                   unlinked_at = NULL, version = git_integration.installation_links.version + 1
             WHERE git_integration.installation_links.status = 'UNLINKED'
            """, nativeQuery = true)
    int activate(
            @Param("orgId") String orgId,
            @Param("installationId") long installationId,
            @Param("userId") String userId,
            @Param("githubUserId") long githubUserId);

    @Query("SELECT l FROM InstallationLink l WHERE l.key.orgId = :orgId AND l.key.installationId = :installationId")
    Optional<InstallationLink> findLink(@Param("orgId") String orgId, @Param("installationId") long installationId);

    @Query(
            value = "SELECT l FROM InstallationLink l WHERE l.key.orgId = :orgId AND l.status = :status",
            countQuery = "SELECT count(l) FROM InstallationLink l WHERE l.key.orgId = :orgId AND l.status = :status")
    Page<InstallationLink> findByStatus(
            @Param("orgId") String orgId, @Param("status") InstallationLink.Status status, Pageable pageable);

    /** @return the link's status, with the row locked until the transaction ends */
    @Query(value = """
            SELECT status FROM git_integration.installation_links
             WHERE org_id = :orgId AND installation_id = :installationId
               FOR UPDATE
            """, nativeQuery = true)
    Optional<String> lockStatus(@Param("orgId") String orgId, @Param("installationId") long installationId);

    /**
     * @return the link's status, share-locked until the transaction ends: an unlink waits for it, and anything written
     *     against the link commits before the unlink can see it
     */
    @Query(value = """
            SELECT status FROM git_integration.installation_links
             WHERE org_id = :orgId AND installation_id = :installationId
               FOR SHARE
            """, nativeQuery = true)
    Optional<String> shareStatus(@Param("orgId") String orgId, @Param("installationId") long installationId);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.installation_links
               SET status = 'UNLINKED', unlinked_at = now(), version = version + 1
             WHERE org_id = :orgId AND installation_id = :installationId AND status = 'ACTIVE'
            """, nativeQuery = true)
    void unlink(@Param("orgId") String orgId, @Param("installationId") long installationId);

    @CrossTenant("lifecycle fan-out: a GitHub event names an installation, and each org that linked it is told")
    @Query(value = """
            SELECT org_id FROM git_integration.installation_links
             WHERE installation_id = :installationId AND status = 'ACTIVE'
             ORDER BY org_id
            """, nativeQuery = true)
    List<String> findActiveOrgIds(@Param("installationId") long installationId);

    @CrossTenant("an uninstalled installation is recorded in the audit log of every org that ever linked it")
    @Query(value = """
            SELECT org_id FROM git_integration.installation_links
             WHERE installation_id = :installationId
             ORDER BY org_id
            """, nativeQuery = true)
    List<String> findOrgIdsEverLinked(@Param("installationId") long installationId);

    /** @return the orgs with an {@code ACTIVE} link, each row locked until the transaction ends */
    @CrossTenant("an installation deleted on GitHub ends every org's link to it")
    @Query(value = """
            SELECT org_id FROM git_integration.installation_links
             WHERE installation_id = :installationId AND status = 'ACTIVE'
             ORDER BY org_id
               FOR UPDATE
            """, nativeQuery = true)
    List<String> lockActiveOrgIds(@Param("installationId") long installationId);

    @CrossTenant("an installation deleted on GitHub ends every org's link to it")
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.installation_links
               SET status = 'UNLINKED', unlinked_at = now(), version = version + 1
             WHERE installation_id = :installationId AND status = 'ACTIVE'
            """, nativeQuery = true)
    int unlinkEveryOrg(@Param("installationId") long installationId);

    @Query(value = """
            SELECT installation_id FROM git_integration.installation_links
             WHERE org_id = :orgId AND status = 'ACTIVE'
             ORDER BY installation_id
            """, nativeQuery = true)
    List<Long> findActiveInstallationIds(@Param("orgId") String orgId);

    /** @return the org's actively linked installations, each link row locked until the transaction ends */
    @Query(value = """
            SELECT installation_id FROM git_integration.installation_links
             WHERE org_id = :orgId AND status = 'ACTIVE'
             ORDER BY installation_id
               FOR UPDATE
            """, nativeQuery = true)
    List<Long> lockActiveInstallationIds(@Param("orgId") String orgId);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE git_integration.installation_links
               SET status = 'UNLINKED', unlinked_at = now(), version = version + 1
             WHERE org_id = :orgId AND status = 'ACTIVE'
            """, nativeQuery = true)
    int unlinkAll(@Param("orgId") String orgId);
}
