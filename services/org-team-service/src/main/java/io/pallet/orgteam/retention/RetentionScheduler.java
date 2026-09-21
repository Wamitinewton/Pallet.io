package io.pallet.orgteam.retention;

import io.pallet.orgteam.invite.InviteExpirySweep;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "pallet.orgteam.retention", name = "enabled", matchIfMissing = true)
class RetentionScheduler {

    private final InviteExpirySweep inviteExpiry;
    private final RetentionSweeps sweeps;
    private final OrgPurgeJob orgPurge;

    RetentionScheduler(InviteExpirySweep inviteExpiry, RetentionSweeps sweeps, OrgPurgeJob orgPurge) {
        this.inviteExpiry = inviteExpiry;
        this.sweeps = sweeps;
        this.orgPurge = orgPurge;
    }

    @Scheduled(fixedDelayString = "${pallet.orgteam.retention.invite-expiry-interval:PT1M}")
    void expireInvites() {
        inviteExpiry.expirePending();
    }

    @Scheduled(fixedDelayString = "${pallet.orgteam.retention.sweep-interval:PT1H}")
    void sweepOutbox() {
        sweeps.sweepPublishedOutbox();
    }

    @Scheduled(fixedDelayString = "${pallet.orgteam.retention.sweep-interval:PT1H}")
    void sweepInbox() {
        sweeps.sweepProcessedEvents();
    }

    @Scheduled(fixedDelayString = "${pallet.orgteam.retention.daily-sweep-interval:P1D}")
    void sweepTerminalInvites() {
        sweeps.sweepTerminalInvites();
    }

    @Scheduled(fixedDelayString = "${pallet.orgteam.retention.daily-sweep-interval:P1D}")
    void sweepRemovedMemberships() {
        sweeps.sweepRemovedMemberships();
    }

    @Scheduled(fixedDelayString = "${pallet.orgteam.retention.daily-sweep-interval:P1D}")
    void purgeDeletedOrgs() {
        orgPurge.purgeExpired();
    }
}
