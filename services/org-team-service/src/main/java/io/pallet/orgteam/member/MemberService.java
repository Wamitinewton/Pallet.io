package io.pallet.orgteam.member;

import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.events.OrgMemberRemoved;
import io.pallet.common.events.OrgMemberRoleChanged;
import io.pallet.orgteam.audit.AuditEvents;
import io.pallet.orgteam.member.MemberExceptions.InvalidSortException;
import io.pallet.orgteam.member.MemberExceptions.MemberNotFoundException;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.org.Organization;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.security.MembershipPolicy;
import io.pallet.orgteam.security.OrgGuard;
import io.pallet.orgteam.support.PageSorting;
import java.time.Clock;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {

    private static final Map<String, String> SORTABLE =
            Map.of("displayName", "displayName", "joinedAt", "joinedAt", "role", "roleRank");
    private static final String TIEBREAKER = "userId";
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("joinedAt"), Sort.Order.asc(TIEBREAKER));

    private final OrgGuard guard;
    private final MembershipRepository memberships;
    private final MembershipPolicy policy;
    private final OutboxWriter outbox;
    private final OrgTeamMetrics metrics;
    private final Clock clock;

    MemberService(
            OrgGuard guard,
            MembershipRepository memberships,
            MembershipPolicy policy,
            OutboxWriter outbox,
            OrgTeamMetrics metrics,
            Clock clock) {
        this.guard = guard;
        this.memberships = memberships;
        this.policy = policy;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberDto> list(String orgId, Role actorRole, MemberFilter filter, PageQuery pageQuery) {
        if (filter.status() == MembershipStatus.REMOVED) {
            policy.checkCanViewRemoved(actorRole);
        }
        Pageable pageable =
                PageSorting.resolve(pageQuery, DEFAULT_SORT, Sort.Order.asc(TIEBREAKER), MemberService::whitelisted);
        Page<Membership> page =
                memberships.search(orgId, filter.status(), filter.roles(), filter.prefixPattern(), pageable);
        return PageResponse.of(page, MemberDto::of);
    }

    @Transactional(readOnly = true)
    public MemberDto get(String orgId, String userId) {
        return MemberDto.of(activeMember(orgId, userId));
    }

    @Transactional
    public MemberDto changeRole(String orgId, String actorUserId, String targetUserId, Role newRole) {
        guard.lockActive(orgId);
        Membership target = activeMember(orgId, targetUserId);
        Membership actor = guard.activeActor(orgId, actorUserId);
        policy.checkCanChangeRole(actor.getRole(), target, newRole);

        Role previousRole = target.getRole();
        if (previousRole == newRole) {
            return MemberDto.of(target);
        }
        target.changeRole(newRole);
        memberships.flush();

        outbox.append(
                OrgMemberRoleChanged.of(orgId, targetUserId, previousRole.keycloakName(), newRole.keycloakName()));
        outbox.append(AuditEvents.memberRoleChanged(
                orgId, actorUserId, targetUserId, previousRole.keycloakName(), newRole.keycloakName()));
        metrics.memberRoleChanged();
        return MemberDto.of(target);
    }

    @Transactional
    public void remove(String orgId, String actorUserId, String targetUserId) {
        guard.lockActive(orgId);
        Membership target = activeMember(orgId, targetUserId);
        Membership actor = guard.activeActor(orgId, actorUserId);
        policy.checkCanRemove(actor.getRole(), actorUserId.equals(targetUserId), target);

        target.remove(clock.instant(), actorUserId);
        memberships.flush();
        memberships.deleteTeamAssignments(orgId, targetUserId);

        outbox.append(OrgMemberRemoved.of(orgId, targetUserId, target.getEmail()));
        outbox.append(AuditEvents.memberRemoved(orgId, actorUserId, targetUserId));
        metrics.memberRemoved();
    }

    @Transactional
    public MemberDto transferOwnership(String orgId, String actorUserId, String targetUserId) {
        Organization organization = guard.lockActive(orgId);
        Membership target = activeMember(orgId, targetUserId);
        Membership actor = guard.activeActor(orgId, actorUserId);
        policy.checkCanTransferOwnership(actor.getRole(), target);

        // The one-active-owner index is checked per statement, so the outgoing owner must be
        // demoted and flushed before the incoming one is promoted.
        Role targetPreviousRole = target.getRole();
        actor.changeRole(Role.ADMIN);
        memberships.flush();
        target.changeRole(Role.OWNER);
        organization.transferOwnership(targetUserId, clock.instant());
        memberships.flush();

        // Events go out in the opposite order so identity-service never sees an org without an owner.
        outbox.append(OrgMemberRoleChanged.of(
                orgId, targetUserId, targetPreviousRole.keycloakName(), Role.OWNER.keycloakName()));
        outbox.append(
                OrgMemberRoleChanged.of(orgId, actorUserId, Role.OWNER.keycloakName(), Role.ADMIN.keycloakName()));
        outbox.append(AuditEvents.ownershipTransferred(orgId, actorUserId, targetUserId));
        metrics.memberRoleChanged();
        metrics.memberRoleChanged();
        return MemberDto.of(target);
    }

    private Membership activeMember(String orgId, String userId) {
        return memberships
                .findByOrgIdAndUserIdAndStatus(orgId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(MemberNotFoundException::new);
    }

    private static Sort.Order whitelisted(Sort.Order order) {
        String property = SORTABLE.get(order.getProperty());
        if (property == null) {
            throw new InvalidSortException(order.getProperty());
        }
        Sort.Order mapped = order.withProperty(property);
        return property.equals("displayName") ? mapped.ignoreCase() : mapped;
    }
}
