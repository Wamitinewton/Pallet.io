package io.pallet.orgteam.retention;

import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.invite.InviteRepository;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.retention.SweepLock.Sweep;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class RetentionSweeps {

    private final SweepRunner runner;
    private final RetentionRepository retention;
    private final InviteRepository invites;
    private final MembershipRepository memberships;
    private final OrgTeamProperties properties;

    RetentionSweeps(
            SweepRunner runner,
            RetentionRepository retention,
            InviteRepository invites,
            MembershipRepository memberships,
            OrgTeamProperties properties) {
        this.runner = runner;
        this.retention = retention;
        this.invites = invites;
        this.memberships = memberships;
        this.properties = properties;
    }

    public long sweepPublishedOutbox() {
        double window = seconds(properties.outbox().retention());
        return runner.run(Sweep.OUTBOX_RETENTION, batch -> retention.deletePublishedOutbox(window, batch));
    }

    public void sweepProcessedEvents() {
        double window = seconds(properties.inbox().retention());
        runner.run(Sweep.INBOX_RETENTION, batch -> retention.deleteProcessedEvents(window, batch));
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
