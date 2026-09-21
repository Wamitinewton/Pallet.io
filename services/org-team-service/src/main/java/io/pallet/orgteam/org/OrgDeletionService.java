package io.pallet.orgteam.org;

import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.OrgDeleted;
import io.pallet.orgteam.app.AppRef;
import io.pallet.orgteam.app.AppRepository;
import io.pallet.orgteam.audit.AuditEvents;
import io.pallet.orgteam.invite.InviteRepository;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import io.pallet.orgteam.team.TeamMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrgDeletionService {

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final TeamMemberRepository teamMembers;
    private final InviteRepository invites;
    private final AppRepository apps;
    private final OutboxWriter outbox;
    private final OrgTeamMetrics metrics;
    private final Clock clock;

    OrgDeletionService(
            OrganizationRepository organizations,
            MembershipRepository memberships,
            TeamMemberRepository teamMembers,
            InviteRepository invites,
            AppRepository apps,
            OutboxWriter outbox,
            OrgTeamMetrics metrics,
            Clock clock) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.teamMembers = teamMembers;
        this.invites = invites;
        this.apps = apps;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public void delete(String orgId, String actorUserId, String confirmedSlug) {
        Organization org = organizations.lockById(orgId).orElseThrow(OrgNotFoundException::new);
        if (org.getStatus() != OrgStatus.ACTIVE) {
            throw new OrgNotFoundException();
        }
        if (!org.getOwnerUserId().equals(actorUserId)) {
            throw new InsufficientRoleException();
        }
        if (!org.getSlug().equals(confirmedSlug)) {
            throw new ConfirmationMismatchException();
        }

        Instant now = clock.instant();
        org.markDeleted(actorUserId, now);
        memberships.removeAllActive(orgId, actorUserId, now);
        teamMembers.deleteAllForOrg(orgId);
        invites.revokeAllPending(orgId, now);
        List<AppRef> deletedApps = apps.findActiveRefs(orgId);
        apps.deleteAllActive(orgId, now);
        organizations.flush();

        deletedApps.forEach(app -> outbox.append(AppDeleted.of(orgId, app.id().toString(), app.slug(), actorUserId)));
        outbox.append(OrgDeleted.of(orgId, actorUserId));
        outbox.append(AuditEvents.orgDeleted(orgId, actorUserId, deletedApps.size()));
        metrics.orgDeleted();
    }
}
