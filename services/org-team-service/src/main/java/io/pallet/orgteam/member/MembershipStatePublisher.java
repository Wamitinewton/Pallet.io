package io.pallet.orgteam.member;

import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.events.Topics;
import io.pallet.common.outbox.OutboxWriter;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends a membership's full current state to the compacted {@code org.membership.changed} topic (ADR-0019).
 * Callers flush the membership first: consumers apply a record only when its version is greater than the one they
 * hold, so a pre-flush version would make the next state look like a duplicate.
 */
@Component
public class MembershipStatePublisher {

    private final OutboxWriter outbox;

    MembershipStatePublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Membership membership) {
        publish(
                membership.getOrgId(),
                membership.getUserId(),
                membership.getRole(),
                membership.getStatus(),
                Objects.requireNonNull(membership.getVersion(), "flush the membership before publishing it"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(MembershipState state) {
        publish(state.orgId(), state.userId(), state.role(), state.status(), state.version());
    }

    /** Erases the membership from the topic after compaction; only for rows this service has deleted. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void tombstone(String orgId, String userId) {
        outbox.appendTombstone(orgId, Topics.ORG_MEMBERSHIP_CHANGED, OrgMembershipChanged.key(orgId, userId));
    }

    private void publish(String orgId, String userId, Role role, MembershipStatus status, long version) {
        outbox.append(
                OrgMembershipChanged.of(orgId, userId, role.keycloakName(), status.name(), version),
                OrgMembershipChanged.key(orgId, userId));
    }
}
