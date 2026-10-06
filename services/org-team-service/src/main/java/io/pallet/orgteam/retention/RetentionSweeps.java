package io.pallet.orgteam.retention;

import io.pallet.common.inbox.InboxProperties;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.outbox.OutboxProperties;
import io.pallet.common.outbox.OutboxRepository;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.invite.InviteRepository;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.member.MembershipRepository.MembershipRow;
import io.pallet.orgteam.member.MembershipStatePublisher;
import io.pallet.orgteam.retention.SweepLock.Sweep;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RetentionSweeps {

    private final SweepRunner runner;
    private final OutboxRepository outbox;
    private final TransactionalInbox inbox;
    private final InviteRepository invites;
    private final MembershipRepository memberships;
    private final MembershipStatePublisher membershipState;
    private final OrgTeamProperties properties;
    private final OutboxProperties outboxProperties;
    private final InboxProperties inboxProperties;

    RetentionSweeps(
            SweepRunner runner,
            OutboxRepository outbox,
            TransactionalInbox inbox,
            InviteRepository invites,
            MembershipRepository memberships,
            MembershipStatePublisher membershipState,
            OrgTeamProperties properties,
            OutboxProperties outboxProperties,
            InboxProperties inboxProperties) {
        this.runner = runner;
        this.outbox = outbox;
        this.inbox = inbox;
        this.invites = invites;
        this.memberships = memberships;
        this.membershipState = membershipState;
        this.properties = properties;
        this.outboxProperties = outboxProperties;
        this.inboxProperties = inboxProperties;
    }

    public long sweepPublishedOutbox() {
        Duration window = outboxProperties.retention();
        return runner.run(Sweep.OUTBOX_RETENTION, batch -> outbox.deletePublishedOlderThan(window, batch));
    }

    public void sweepProcessedEvents() {
        Duration window = inboxProperties.retention();
        runner.run(Sweep.INBOX_RETENTION, batch -> inbox.deleteProcessedOlderThan(window, batch));
    }

    public void sweepTerminalInvites() {
        double window = seconds(properties.retention().terminalInvites());
        runner.run(Sweep.TERMINAL_INVITES, batch -> invites.deleteTerminalOlderThan(window, batch));
    }

    public void sweepRemovedMemberships() {
        double window = seconds(properties.retention().removedMemberships());
        // Without a tombstone, a consumer would keep the deleted row's REMOVED version and drop every record of a
        // re-invited membership, whose version starts again at 0.
        runner.run(Sweep.REMOVED_MEMBERSHIPS, batch -> {
            List<MembershipRow> deleted = memberships.deleteRemovedOlderThan(window, batch);
            deleted.forEach(row -> membershipState.tombstone(row.getOrgId(), row.getUserId()));
            return deleted.size();
        });
    }

    static double seconds(Duration window) {
        return window.toMillis() / 1000.0;
    }
}
