package io.pallet.gitintegration.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;

/** Rows the authorization layer reads, written straight into the read models and removed again after each test. */
public final class ReadModelFixtures {

    private final JdbcTemplate jdbc;
    private final List<String> orgIds = new ArrayList<>();
    private final List<Long> installationIds = new ArrayList<>();

    public ReadModelFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String newOrg() {
        String orgId = "org-" + UUID.randomUUID();
        orgIds.add(orgId);
        return orgId;
    }

    public String newMember(String orgId, String role, String status) {
        String userId = "user-" + UUID.randomUUID();
        addMember(orgId, userId, role, status);
        return userId;
    }

    public void addMember(String orgId, String userId, String role, String status) {
        jdbc.update("""
                INSERT INTO git_integration.org_memberships (org_id, user_id, role, status, source_version)
                VALUES (?, ?, ?, ?, 1)
                """, orgId, userId, role, status);
    }

    public void setStatus(String orgId, String userId, String status) {
        jdbc.update(
                "UPDATE git_integration.org_memberships SET status = ? WHERE org_id = ? AND user_id = ?",
                status,
                orgId,
                userId);
    }

    public void setRole(String orgId, String userId, String role) {
        jdbc.update(
                "UPDATE git_integration.org_memberships SET role = ? WHERE org_id = ? AND user_id = ?",
                role,
                orgId,
                userId);
    }

    public void deleteOrg(String orgId) {
        jdbc.update("INSERT INTO git_integration.deleted_orgs (org_id, deleted_at) VALUES (?, now())", orgId);
    }

    public UUID newApp(String orgId, String status) {
        UUID appId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO git_integration.apps (app_id, org_id, slug, status) VALUES (?, ?, ?, ?)",
                appId,
                orgId,
                "app-" + appId.toString().substring(0, 8),
                status);
        return appId;
    }

    public long newInstallation() {
        long installationId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        jdbc.update("""
                INSERT INTO git_integration.installations
                    (installation_id, account_id, account_login, account_type, repository_selection, status)
                VALUES (?, ?, 'acme', 'Organization', 'selected', 'ACTIVE')
                """, installationId, installationId);
        installationIds.add(installationId);
        return installationId;
    }

    public void linkInstallation(long installationId, String orgId, String status) {
        jdbc.update("""
                INSERT INTO git_integration.installation_links
                    (installation_id, org_id, status, linked_by_user_id, linked_by_github_user_id)
                VALUES (?, ?, ?, 'user-linker', 1)
                """, installationId, orgId, status);
    }

    /** Registers an installation the service itself created, so {@link #cleanUp()} removes it and its rows. */
    public long track(long installationId) {
        installationIds.add(installationId);
        return installationId;
    }

    public long newInstallationId() {
        return track(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
    }

    /** An {@code ACTIVE}, verified repo link; the org must have an installation link to {@code installationId}. */
    public UUID newRepoLink(String orgId, long installationId, long repoId) {
        UUID appId = newApp(orgId, "ACTIVE");
        jdbc.update("""
                INSERT INTO git_integration.repo_links
                    (app_id, org_id, installation_id, repo_id, repo_full_name, production_branch, status,
                     verified_by_user_id, verified_github_user_id, verified_github_login, verified_permission,
                     access_verified_at, access_checked_at)
                VALUES (?, ?, ?, ?, 'octo-org/api', 'main', 'ACTIVE', 'user-verifier', 1, 'verifier', 'push',
                        now(), now())
                """, appId, orgId, installationId, repoId);
        return appId;
    }

    /**
     * Marks each installation {@code DELETED} first: that waits out a repository sync holding the row, and makes any
     * later one skip it, so no sync writes rows between the deletes below.
     */
    public void cleanUp() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM git_integration.manual_build_requests WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM git_integration.check_runs WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM git_integration.repo_links WHERE org_id = ?", orgId);
        });
        installationIds.forEach(id -> {
            jdbc.update("UPDATE git_integration.installations SET status = 'DELETED' WHERE installation_id = ?", id);
            jdbc.update("DELETE FROM git_integration.repo_links WHERE installation_id = ?", id);
            jdbc.update("DELETE FROM git_integration.installation_repositories WHERE installation_id = ?", id);
            jdbc.update("DELETE FROM git_integration.installation_links WHERE installation_id = ?", id);
            jdbc.update("DELETE FROM git_integration.installations WHERE installation_id = ?", id);
            jdbc.update("DELETE FROM git_integration.sync_cursors WHERE name = ?", "repo-sync:" + id);
        });
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM git_integration.outbox_events WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM git_integration.apps WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM git_integration.org_memberships WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM git_integration.deleted_orgs WHERE org_id = ?", orgId);
        });
        installationIds.clear();
        orgIds.clear();
    }
}
