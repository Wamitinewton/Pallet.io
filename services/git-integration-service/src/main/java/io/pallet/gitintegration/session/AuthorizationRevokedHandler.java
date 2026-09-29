package io.pallet.gitintegration.session;

import io.pallet.common.outbox.OutboxWriter;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.delivery.DeliveryContext;
import io.pallet.gitintegration.delivery.DeliveryHandler;
import io.pallet.gitintegration.delivery.DeliveryOutcome;
import io.pallet.gitintegration.delivery.SubscribedEvents;
import io.pallet.gitintegration.delivery.payload.AppAuthorizationPayload;
import io.pallet.gitintegration.projection.MembershipProjectionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code github_app_authorization.revoked}: ends every session issued to that GitHub user. Repo links the user verified
 * are left alone; the installation token still reaches them, and the daily re-verification decides. The audit rows are
 * appended before the sessions are deleted, so a retried delivery still finds the subjects to audit.
 */
@Component
class AuthorizationRevokedHandler implements DeliveryHandler {

    static final String REVOKED = "revoked";
    static final String UNSUPPORTED_ACTION = "UNSUPPORTED_ACTION";

    private final GitHubUserSessionStore sessions;
    private final MembershipProjectionRepository memberships;
    private final OutboxWriter outbox;
    private final Clock clock;

    AuthorizationRevokedHandler(
            GitHubUserSessionStore sessions,
            MembershipProjectionRepository memberships,
            OutboxWriter outbox,
            Clock clock) {
        this.sessions = sessions;
        this.memberships = memberships;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public Set<String> events() {
        return Set.of(SubscribedEvents.GITHUB_APP_AUTHORIZATION);
    }

    @Override
    public DeliveryOutcome handle(DeliveryContext context) {
        AppAuthorizationPayload payload = context.payload(AppAuthorizationPayload.class);
        if (!REVOKED.equals(payload.action())) {
            return DeliveryOutcome.ignored(UNSUPPORTED_ACTION);
        }
        long githubUserId = payload.senderId();
        List<String> subjects = sessions.findSubjectsByGithubUser(githubUserId);
        Instant now = clock.instant();
        for (String sub : subjects) {
            for (String orgId : memberships.findActiveOrgIds(sub)) {
                outbox.append(AuditEvents.githubAuthorizationRevoked(orgId, sub, githubUserId, now));
            }
        }
        sessions.deleteByGithubUser(githubUserId);
        return DeliveryOutcome.PROCESSED;
    }
}
