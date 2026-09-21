package io.pallet.orgteam.security;

import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.security.AccessExceptions.ReauthenticationRequiredException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class RecentAuthentication {

    private final Clock clock;
    private final Duration window;
    private final OrgTeamMetrics metrics;

    public RecentAuthentication(Clock clock, Duration window, OrgTeamMetrics metrics) {
        this.clock = clock;
        this.window = window;
        this.metrics = metrics;
    }

    public void require(AccessContext context) {
        Instant authTime = context.authTime();
        if (authTime == null || Duration.between(authTime, clock.instant()).compareTo(window) > 0) {
            metrics.authzDenied(MetricsCatalog.DENIED_REAUTHENTICATION);
            throw new ReauthenticationRequiredException();
        }
    }
}
