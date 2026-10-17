package io.pallet.buildqueue.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Rows the authorization layer reads, written straight into the tables and removed again by {@link #cleanUp()}. */
public final class ReadModelFixtures {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private final JdbcTemplate jdbc;
    private final List<String> orgIds = new ArrayList<>();

    public ReadModelFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String newOrg() {
        String orgId = "org-" + UUID.randomUUID();
        orgIds.add(orgId);
        jdbc.update("INSERT INTO build_queue.orgs (org_id, status) VALUES (?, 'ACTIVE')", orgId);
        return orgId;
    }

    public void deleteOrg(String orgId) {
        jdbc.update(
                "UPDATE build_queue.orgs SET status = 'DELETED', deleted_at = now(), updated_at = now()"
                        + " WHERE org_id = ?",
                orgId);
    }

    public String newMember(String orgId, String role, String status) {
        String userId = "user-" + UUID.randomUUID();
        addMember(orgId, userId, role, status);
        return userId;
    }

    public void addMember(String orgId, String userId, String role, String status) {
        jdbc.update("""
                INSERT INTO build_queue.org_memberships (org_id, user_id, role, status, source_version)
                VALUES (?, ?, ?, ?, 1)
                """, orgId, userId, role, status);
    }

    public void setStatus(String orgId, String userId, String status) {
        jdbc.update(
                "UPDATE build_queue.org_memberships SET status = ? WHERE org_id = ? AND user_id = ?",
                status,
                orgId,
                userId);
    }

    public void setRole(String orgId, String userId, String role) {
        jdbc.update(
                "UPDATE build_queue.org_memberships SET role = ? WHERE org_id = ? AND user_id = ?",
                role,
                orgId,
                userId);
    }

    public String newApp(String orgId, String status) {
        String appId = "app-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO build_queue.apps (app_id, org_id, slug, status, deleted_at)"
                        + " VALUES (?, ?, ?, ?, CASE WHEN ? = 'DELETED' THEN now() END)",
                appId,
                orgId,
                appId.substring(0, 12),
                status,
                status);
        return appId;
    }

    /** A waiting build on its own branch, so any number fit beside each other under the coalescing index. */
    public UUID newBuild(String orgId, String appId) {
        UUID buildId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO build_queue.builds
                    (build_id, org_id, app_id, branch, commit_sha, push_event_id, trigger, priority_class,
                     provider, installation_id, repo_id, repo, status)
                VALUES (?, ?, ?, ?, ?, ?, 'WEBHOOK', 1, 'GITHUB', 1, 1, 'octo-org/api', 'QUEUED')
                """, buildId, orgId, appId, "branch-" + buildId, SHA, UUID.randomUUID());
        return buildId;
    }

    public void cleanUp() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM build_queue.builds WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM build_queue.apps WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM build_queue.org_memberships WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM build_queue.orgs WHERE org_id = ?", orgId);
        });
        orgIds.clear();
    }
}
