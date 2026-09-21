package io.pallet.orgteam.security;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class OrgFixtures {

    private final JdbcTemplate jdbc;
    private final List<String> orgIds = new ArrayList<>();

    public OrgFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String newOrg(String status) {
        String orgId = "org-" + UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO org_team.organizations (org_id, name, slug, owner_user_id, status)
                        VALUES (?, ?, ?, ?, ?)
                        """, orgId, "Org " + orgId, orgId, "owner-" + orgId, status);
        orgIds.add(orgId);
        return orgId;
    }

    public String newActiveOrg() {
        return newOrg("ACTIVE");
    }

    public String newMember(String orgId, String role, String status) {
        String userId = "user-" + UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO org_team.memberships (org_id, user_id, email, display_name, role, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, orgId, userId, userId + "@example.com", userId, role, status);
        return userId;
    }

    public void setRole(String orgId, String userId, String role) {
        jdbc.update("UPDATE org_team.memberships SET role = ? WHERE org_id = ? AND user_id = ?", role, orgId, userId);
    }

    public void setStatus(String orgId, String userId, String status) {
        jdbc.update(
                "UPDATE org_team.memberships SET status = ? WHERE org_id = ? AND user_id = ?", status, orgId, userId);
    }

    public void cleanUp() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.memberships WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.organizations WHERE org_id = ?", orgId);
        });
        orgIds.clear();
    }
}
