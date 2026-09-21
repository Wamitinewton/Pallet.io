package io.pallet.orgteam.retention;

import io.pallet.orgteam.app.AppRepository;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.invite.InviteRepository;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.org.OrganizationRepository;
import io.pallet.orgteam.retention.SweepLock.Sweep;
import io.pallet.orgteam.team.TeamMemberRepository;
import io.pallet.orgteam.team.TeamRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OrgPurgeJob {

    static final String PURGED_NAME = "[purged]";

    private static final Logger log = LoggerFactory.getLogger(OrgPurgeJob.class);

    private final OrganizationRepository organizations;
    private final TeamMemberRepository teamMembers;
    private final AppRepository apps;
    private final TeamRepository teams;
    private final InviteRepository invites;
    private final MembershipRepository memberships;
    private final SweepLock lock;
    private final SweepRunner runner;
    private final OrgTeamMetrics metrics;
    private final TransactionTemplate transaction;
    private final OrgTeamProperties.Retention settings;

    OrgPurgeJob(
            OrganizationRepository organizations,
            TeamMemberRepository teamMembers,
            AppRepository apps,
            TeamRepository teams,
            InviteRepository invites,
            MembershipRepository memberships,
            SweepLock lock,
            SweepRunner runner,
            OrgTeamMetrics metrics,
            PlatformTransactionManager transactionManager,
            OrgTeamProperties properties) {
        this.organizations = organizations;
        this.teamMembers = teamMembers;
        this.apps = apps;
        this.teams = teams;
        this.invites = invites;
        this.memberships = memberships;
        this.lock = lock;
        this.runner = runner;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.settings = properties.retention();
    }

    /** @return number of organizations purged by this run */
    public int purgeExpired() {
        double window = RetentionSweeps.seconds(settings.deletedOrgs());
        List<String> candidates = organizations.findPurgeable(window, settings.orgPurgeBatchSize());
        int purged = 0;
        for (String orgId : candidates) {
            Long rows;
            try {
                rows = transaction.execute(status -> purge(orgId, window));
            } catch (RuntimeException e) {
                metrics.sweepFailure(Sweep.ORG_PURGE.metricTag());
                log.error(
                        "Org purge failed orgId={} error={}",
                        orgId,
                        e.getClass().getSimpleName());
                continue;
            }
            if (rows == null) {
                continue;
            }
            purged++;
            runner.count(Sweep.ORG_PURGE, rows);
        }
        if (purged > 0) {
            log.info("Purged {} deleted organizations", purged);
        }
        return purged;
    }

    private Long purge(String orgId, double window) {
        if (!lock.tryAcquire(Sweep.ORG_PURGE)
                || organizations.lockIfPurgeable(orgId, window).isEmpty()) {
            return null;
        }
        long rows = teamMembers.deleteAllForOrg(orgId);
        rows += apps.deleteAllForOrg(orgId);
        rows += teams.deleteAllForOrg(orgId);
        rows += invites.deleteAllForOrg(orgId);
        rows += memberships.deleteAllForOrg(orgId);
        organizations.markPurged(orgId, PURGED_NAME);
        return rows + 1;
    }
}
