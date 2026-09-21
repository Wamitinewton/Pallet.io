package io.pallet.orgteam.invite;

import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.retention.SweepLock.Sweep;
import io.pallet.orgteam.retention.SweepRunner;
import org.springframework.stereotype.Component;

@Component
public class InviteExpirySweep {

    private final SweepRunner runner;
    private final InviteRepository invites;
    private final OrgTeamMetrics metrics;

    InviteExpirySweep(SweepRunner runner, InviteRepository invites, OrgTeamMetrics metrics) {
        this.runner = runner;
        this.invites = invites;
        this.metrics = metrics;
    }

    public long expirePending() {
        long expired = runner.run(Sweep.INVITE_EXPIRY, invites::expirePending);
        metrics.invitesExpired(expired);
        return expired;
    }
}
