package io.pallet.build.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Rows the authorization layer and intake read, written straight into the tables and removed by {@link #cleanUp()}. */
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
        jdbc.update("INSERT INTO build.orgs (org_id, status) VALUES (?, 'ACTIVE')", orgId);
        return orgId;
    }

    public void deleteOrg(String orgId) {
        jdbc.update(
                "UPDATE build.orgs SET status = 'DELETED', deleted_at = now(), updated_at = now() WHERE org_id = ?",
                orgId);
    }

    /** {@code role} is the upper-case name the membership projection stores. */
    public String newMember(String orgId, String role, String status) {
        String userId = "user-" + UUID.randomUUID();
        addMember(orgId, userId, role, status);
        return userId;
    }

    public void addMember(String orgId, String userId, String role, String status) {
        jdbc.update("""
                INSERT INTO build.org_memberships (org_id, user_id, role, status, source_version)
                VALUES (?, ?, ?, ?, 1)
                """, orgId, userId, role, status);
    }

    public void setStatus(String orgId, String userId, String status) {
        jdbc.update(
                "UPDATE build.org_memberships SET status = ? WHERE org_id = ? AND user_id = ?", status, orgId, userId);
    }

    public void setRole(String orgId, String userId, String role) {
        jdbc.update("UPDATE build.org_memberships SET role = ? WHERE org_id = ? AND user_id = ?", role, orgId, userId);
    }

    public String newApp(String orgId, String status) {
        String appId = "app-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO build.apps (app_id, org_id, status, deleted_at)"
                        + " VALUES (?, ?, ?, CASE WHEN ? = 'DELETED' THEN now() END)",
                appId,
                orgId,
                status,
                status);
        return appId;
    }

    public void deleteApp(String appId) {
        jdbc.update(
                "UPDATE build.apps SET status = 'DELETED', deleted_at = now(), updated_at = now() WHERE app_id = ?",
                appId);
    }

    public void setSettings(String orgId, String appId, String strategy, String dockerfilePath) {
        jdbc.update(
                "INSERT INTO build.build_settings (org_id, app_id, strategy, dockerfile_path) VALUES (?, ?, ?, ?)",
                orgId,
                appId,
                strategy,
                dockerfilePath);
    }

    /** An accepted build, waiting for a slot. */
    public String newBuild(String orgId, String appId) {
        String buildId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO build.builds
                    (build_id, org_id, app_id, branch, commit_sha, push_event_id, trigger, attempt, provider,
                     installation_id, repo_id, repo, root_directory, start_by, max_duration_seconds,
                     strategy_setting, dockerfile_path, status, job_name)
                VALUES (?, ?, ?, 'main', ?, ?, 'WEBHOOK', 1, 'GITHUB', 1, 1, 'octo-org/api', '',
                        now() + interval '5 minutes', 1800, 'AUTO', 'Dockerfile', 'ACCEPTED', ?)
                """, buildId, orgId, appId, SHA, UUID.randomUUID(), "build-" + buildId);
        return buildId;
    }

    public void cleanUp() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM build.builds WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM build.build_settings WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM build.apps WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM build.org_memberships WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM build.orgs WHERE org_id = ?", orgId);
        });
        orgIds.clear();
    }
}
