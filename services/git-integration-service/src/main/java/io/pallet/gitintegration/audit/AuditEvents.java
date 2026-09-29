package io.pallet.gitintegration.audit;

import io.pallet.common.events.AuditEventRecorded;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * This service's {@link AuditEventRecorded}s: the actor, the org, and ids. Never a token, a {@code code}, or any name
 * beyond what the org already sees.
 */
public final class AuditEvents {

    public static final String GITHUB_AUTHORIZATION_REVOKED = "github.authorization.revoked";
    public static final String INSTALLATION_LINKED = "git.installation.linked";
    public static final String INSTALLATION_UNLINKED = "git.installation.unlinked";
    public static final String INSTALLATION_SUSPENDED = "git.installation.suspended";
    public static final String INSTALLATION_UNSUSPENDED = "git.installation.unsuspended";
    public static final String INSTALLATION_UNINSTALLED = "git.installation.uninstalled";
    public static final String REPO_LINK_CREATED = "git.repo_link.created";
    public static final String REPO_LINK_UPDATED = "git.repo_link.updated";
    public static final String REPO_LINK_VERIFIER_CHANGED = "git.repo_link.verifier_changed";
    public static final String REPO_LINK_DISCONNECTED = "git.repo_link.disconnected";
    public static final String REPO_LINK_VERIFIER_ACCESS_LOST = "git.repo_link.verifier_access_lost";
    public static final String BUILD_REQUESTED = "git.build.requested";

    private AuditEvents() {}

    public static AuditEventRecorded githubAuthorizationRevoked(
            String orgId, String userId, long githubUserId, Instant occurredAt) {
        return event(
                orgId,
                userId,
                GITHUB_AUTHORIZATION_REVOKED,
                "github-user:" + githubUserId,
                Map.of("githubUserId", githubUserId),
                occurredAt);
    }

    public static AuditEventRecorded installationLinked(
            String orgId, String userId, long installationId, String accountLogin, long githubUserId, Instant at) {
        return event(
                orgId,
                userId,
                INSTALLATION_LINKED,
                installation(installationId),
                Map.of("installationId", installationId, "accountLogin", accountLogin, "githubUserId", githubUserId),
                at);
    }

    public static AuditEventRecorded installationUnlinked(
            String orgId, String actor, long installationId, String reason, int repoLinksDisconnected, Instant at) {
        return event(
                orgId,
                actor,
                INSTALLATION_UNLINKED,
                installation(installationId),
                Map.of(
                        "installationId",
                        installationId,
                        "reason",
                        reason,
                        "repoLinksDisconnected",
                        repoLinksDisconnected),
                at);
    }

    public static AuditEventRecorded installationSuspended(String orgId, long installationId, Instant at) {
        return event(
                orgId,
                Actor.SYSTEM.id(),
                INSTALLATION_SUSPENDED,
                installation(installationId),
                Map.of("installationId", installationId),
                at);
    }

    public static AuditEventRecorded installationUnsuspended(String orgId, long installationId, Instant at) {
        return event(
                orgId,
                Actor.SYSTEM.id(),
                INSTALLATION_UNSUSPENDED,
                installation(installationId),
                Map.of("installationId", installationId),
                at);
    }

    public static AuditEventRecorded installationUninstalled(
            String orgId, long installationId, String reason, long daysUnused, Instant at) {
        return event(
                orgId,
                Actor.SYSTEM.id(),
                INSTALLATION_UNINSTALLED,
                installation(installationId),
                Map.of("installationId", installationId, "reason", reason, "daysUnused", daysUnused),
                at);
    }

    public static AuditEventRecorded repoLinkCreated(
            String orgId,
            String actor,
            UUID appId,
            long installationId,
            long repoId,
            String repository,
            String branch,
            String verifierLogin,
            String permission,
            Instant at) {
        return event(
                orgId,
                actor,
                REPO_LINK_CREATED,
                app(appId),
                Map.of(
                        "installationId", installationId,
                        "repoId", repoId,
                        "repository", repository,
                        "branch", branch,
                        "verifierLogin", verifierLogin,
                        "permission", permission),
                at);
    }

    public static AuditEventRecorded repoLinkUpdated(
            String orgId, String actor, UUID appId, List<String> changedFields, Instant at) {
        return event(orgId, actor, REPO_LINK_UPDATED, app(appId), Map.of("changedFields", changedFields), at);
    }

    public static AuditEventRecorded repoLinkVerifierChanged(
            String orgId,
            String actor,
            UUID appId,
            String previousLogin,
            String verifierLogin,
            String permission,
            Instant at) {
        return event(
                orgId,
                actor,
                REPO_LINK_VERIFIER_CHANGED,
                app(appId),
                Map.of("previousLogin", previousLogin, "verifierLogin", verifierLogin, "permission", permission),
                at);
    }

    public static AuditEventRecorded repoLinkDisconnected(
            String orgId, String actor, UUID appId, String reason, Instant at) {
        return event(orgId, actor, REPO_LINK_DISCONNECTED, app(appId), Map.of("reason", reason), at);
    }

    public static AuditEventRecorded repoLinkVerifierAccessLost(
            String orgId, UUID appId, String verifierLogin, String previousRole, String currentRole, Instant at) {
        return event(
                orgId,
                Actor.SYSTEM.id(),
                REPO_LINK_VERIFIER_ACCESS_LOST,
                app(appId),
                Map.of("verifierLogin", verifierLogin, "previousRole", previousRole, "currentRole", currentRole),
                at);
    }

    public static AuditEventRecorded buildRequested(
            String orgId, String actor, UUID appId, String branch, String commitSha, UUID eventId, Instant at) {
        return event(
                orgId,
                actor,
                BUILD_REQUESTED,
                app(appId),
                Map.of("branch", branch, "commitSha", commitSha, "eventId", eventId.toString()),
                at);
    }

    private static String app(UUID appId) {
        return "app:" + appId;
    }

    private static String installation(long installationId) {
        return "github-installation:" + installationId;
    }

    private static AuditEventRecorded event(
            String orgId, String actor, String action, String resource, Map<String, Object> context, Instant at) {
        return new AuditEventRecorded(
                UUID.randomUUID(), AuditEventRecorded.TYPE, orgId, at, actor, action, resource, context);
    }
}
