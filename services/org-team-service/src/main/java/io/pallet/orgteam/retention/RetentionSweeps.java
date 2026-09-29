package io.pallet.orgteam.retention;

import io.pallet.common.inbox.InboxProperties;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.outbox.OutboxProperties;
import io.pallet.common.outbox.OutboxRepository;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.invite.InviteRepository;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.retention.SweepLock.Sweep;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class RetentionSweeps {

    private final SweepRunner runner;
    private final OutboxRepository outbox;
    private final TransactionalInbox inbox;
    private final InviteRepository invites;
    private final MembershipRepository memberships;
    private final OrgTeamProperties properties;
    private final OutboxProperties outboxProperties;
    private final InboxProperties inboxProperties;

    RetentionSweeps(
            SweepRunner runner,
            OutboxRepository outbox,
            TransactionalInbox inbox,
            InviteRepository invites,
            MembershipRepository memberships,
            OrgTeamProperties properties,
            OutboxProperties outboxProperties,
            InboxProperties inboxProperties) {
        this.runner = runner;
        this.outbox = outbox;
        this.inbox = inbox;
        this.invites = invites;
        this.memberships = memberships;
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
        runner.run(Sweep.REMOVED_MEMBERSHIPS, batch -> memberships.deleteRemovedOlderThan(window, batch));
    }

    static double seconds(Duration window) {
        return window.toMillis() / 1000.0;
    }
}
