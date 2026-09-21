package io.pallet.orgteam.audit;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.orgteam.app.CloudProvider;
import java.util.Map;

public final class AuditEvents {

    public static final String ORG_CREATED = "org.created";
    public static final String ORG_RENAMED = "org.renamed";
    public static final String ORG_DELETED = "org.deleted";

    public static final String MEMBER_ROLE_CHANGED = "member.role_changed";
    public static final String MEMBER_REMOVED = "member.removed";
    public static final String MEMBER_LEFT = "member.left";
    public static final String OWNERSHIP_TRANSFERRED = "org.ownership_transferred";

    public static final String INVITE_CREATED = "invite.created";
    public static final String INVITE_RESENT = "invite.resent";
    public static final String INVITE_REVOKED = "invite.revoked";
    public static final String INVITE_ACCEPTED = "invite.accepted";
    public static final String INVITE_REJECTED = "invite.rejected";
    public static final String MEMBER_ADDED = "member.added";

    public static final String TEAM_CREATED = "team.created";
    public static final String TEAM_RENAMED = "team.renamed";
    public static final String TEAM_DELETED = "team.deleted";
    public static final String TEAM_MEMBER_ADDED = "team.member_added";
    public static final String TEAM_MEMBER_REMOVED = "team.member_removed";

    public static final String APP_CREATED = "app.created";
    public static final String APP_UPDATED = "app.updated";
    public static final String APP_DELETED = "app.deleted";

    private AuditEvents() {}

    public static AuditEventRecorded orgCreated(String orgId, String actorUserId) {
        return AuditEventRecorded.of(orgId, actorUserId, ORG_CREATED, orgId, Map.of());
    }

    public static AuditEventRecorded orgRenamed(String orgId, String actorUserId, String from, String to) {
        return AuditEventRecorded.of(orgId, actorUserId, ORG_RENAMED, orgId, Map.of("from", from, "to", to));
    }

    public static AuditEventRecorded orgDeleted(String orgId, String actorUserId, int appsDeleted) {
        return AuditEventRecorded.of(orgId, actorUserId, ORG_DELETED, orgId, Map.of("appsDeleted", appsDeleted));
    }

    public static AuditEventRecorded memberRoleChanged(
            String orgId, String actorUserId, String userId, String previousRole, String newRole) {
        return AuditEventRecorded.of(
                orgId,
                actorUserId,
                MEMBER_ROLE_CHANGED,
                userId,
                Map.of("previousRole", previousRole, "newRole", newRole));
    }

    public static AuditEventRecorded memberRemoved(String orgId, String actorUserId, String userId) {
        String action = actorUserId.equals(userId) ? MEMBER_LEFT : MEMBER_REMOVED;
        return AuditEventRecorded.of(orgId, actorUserId, action, userId, Map.of());
    }

    public static AuditEventRecorded ownershipTransferred(String orgId, String actorUserId, String newOwnerUserId) {
        return AuditEventRecorded.of(
                orgId, actorUserId, OWNERSHIP_TRANSFERRED, orgId, Map.of("from", actorUserId, "to", newOwnerUserId));
    }

    public static AuditEventRecorded inviteCreated(String orgId, String actorUserId, String inviteId, String role) {
        return AuditEventRecorded.of(
                orgId, actorUserId, INVITE_CREATED, inviteId, Map.of("inviteId", inviteId, "role", role));
    }

    public static AuditEventRecorded inviteResent(
            String orgId, String actorUserId, String inviteId, String role, int sendCount) {
        return AuditEventRecorded.of(
                orgId,
                actorUserId,
                INVITE_RESENT,
                inviteId,
                Map.of("inviteId", inviteId, "role", role, "sendCount", sendCount));
    }

    public static AuditEventRecorded inviteRevoked(String orgId, String actorUserId, String inviteId, String role) {
        return AuditEventRecorded.of(
                orgId, actorUserId, INVITE_REVOKED, inviteId, Map.of("inviteId", inviteId, "role", role));
    }

    public static AuditEventRecorded inviteAccepted(String orgId, String userId, String inviteId, String role) {
        return AuditEventRecorded.of(
                orgId, userId, INVITE_ACCEPTED, inviteId, Map.of("inviteId", inviteId, "role", role));
    }

    public static AuditEventRecorded memberAdded(String orgId, String userId, String role) {
        return AuditEventRecorded.of(orgId, userId, MEMBER_ADDED, userId, Map.of("role", role));
    }

    public static AuditEventRecorded inviteRejected(String orgId, String userId, String inviteId, String reason) {
        return AuditEventRecorded.of(
                orgId, userId, INVITE_REJECTED, inviteId, Map.of("inviteId", inviteId, "reason", reason));
    }

    public static AuditEventRecorded teamCreated(String orgId, String actorUserId, String teamId) {
        return AuditEventRecorded.of(orgId, actorUserId, TEAM_CREATED, teamId, Map.of("teamId", teamId));
    }

    public static AuditEventRecorded teamRenamed(String orgId, String actorUserId, String teamId) {
        return AuditEventRecorded.of(orgId, actorUserId, TEAM_RENAMED, teamId, Map.of("teamId", teamId));
    }

    public static AuditEventRecorded teamDeleted(String orgId, String actorUserId, String teamId) {
        return AuditEventRecorded.of(orgId, actorUserId, TEAM_DELETED, teamId, Map.of("teamId", teamId));
    }

    public static AuditEventRecorded teamMemberAdded(String orgId, String actorUserId, String teamId, String userId) {
        return AuditEventRecorded.of(
                orgId, actorUserId, TEAM_MEMBER_ADDED, teamId, Map.of("teamId", teamId, "userId", userId));
    }

    public static AuditEventRecorded teamMemberRemoved(String orgId, String actorUserId, String teamId, String userId) {
        return AuditEventRecorded.of(
                orgId, actorUserId, TEAM_MEMBER_REMOVED, teamId, Map.of("teamId", teamId, "userId", userId));
    }

    public static AuditEventRecorded appCreated(
            String orgId, String actorUserId, String appId, String slug, CloudProvider provider, String region) {
        return AuditEventRecorded.of(
                orgId,
                actorUserId,
                APP_CREATED,
                appId,
                Map.of("appId", appId, "slug", slug, "cloudProvider", provider.name(), "region", region));
    }

    public static AuditEventRecorded appUpdated(String orgId, String actorUserId, String appId) {
        return AuditEventRecorded.of(orgId, actorUserId, APP_UPDATED, appId, Map.of("appId", appId));
    }

    public static AuditEventRecorded appDeleted(String orgId, String actorUserId, String appId, String slug) {
        return AuditEventRecorded.of(orgId, actorUserId, APP_DELETED, appId, Map.of("appId", appId, "slug", slug));
    }
}
