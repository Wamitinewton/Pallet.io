package io.pallet.orgteam.team;

import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.orgteam.audit.AuditEvents;
import io.pallet.orgteam.config.ConstraintViolations;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.invite.InviteExceptions.QuotaExceededException;
import io.pallet.orgteam.member.MemberDto;
import io.pallet.orgteam.member.MemberExceptions.InvalidSortException;
import io.pallet.orgteam.member.MemberExceptions.MemberNotFoundException;
import io.pallet.orgteam.member.Membership;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.member.MembershipStatus;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.security.OrgGuard;
import io.pallet.orgteam.support.PageSorting;
import io.pallet.orgteam.support.Slugs;
import io.pallet.orgteam.team.TeamExceptions.AlreadyInTeamException;
import io.pallet.orgteam.team.TeamExceptions.SlugTakenException;
import io.pallet.orgteam.team.TeamExceptions.TeamNotFoundException;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {

    static final String SLUG_CONSTRAINT = "teams_org_id_slug_key";

    private static final Set<String> SORTABLE_TEAMS = Set.of("name", "createdAt");
    private static final Set<String> SORTABLE_MEMBERS = Set.of("displayName", "joinedAt");
    private static final Sort DEFAULT_TEAM_SORT = Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id"));
    private static final Sort DEFAULT_MEMBER_SORT =
            Sort.by(Sort.Order.asc("displayName").ignoreCase(), Sort.Order.asc("userId"));

    private final OrgGuard guard;
    private final MembershipRepository memberships;
    private final TeamRepository teams;
    private final TeamMemberRepository teamMembers;
    private final OutboxWriter outbox;
    private final OrgTeamProperties.Limits limits;
    private final Clock clock;

    TeamService(
            OrgGuard guard,
            MembershipRepository memberships,
            TeamRepository teams,
            TeamMemberRepository teamMembers,
            OutboxWriter outbox,
            OrgTeamProperties properties,
            Clock clock) {
        this.guard = guard;
        this.memberships = memberships;
        this.teams = teams;
        this.teamMembers = teamMembers;
        this.outbox = outbox;
        this.limits = properties.limits();
        this.clock = clock;
    }

    @Transactional
    public TeamDto create(String orgId, String actorUserId, String rawName, String rawSlug) {
        guard.lockAsAtLeast(orgId, actorUserId, Role.ADMIN);

        String name = rawName.strip();
        String slug = rawSlug == null ? Slugs.fromName(name) : rawSlug;
        if (teams.countByOrgId(orgId) >= limits.maxTeamsPerOrg()) {
            throw new QuotaExceededException("This organization has reached its team limit.");
        }
        if (teams.existsByOrgIdAndSlug(orgId, slug)) {
            throw new SlugTakenException();
        }

        UUID teamId = UUID.randomUUID();
        Team team = new Team(teamId, orgId, name, slug, clock.instant());
        try {
            teams.saveAndFlush(team);
        } catch (DataIntegrityViolationException violation) {
            ConstraintViolations.requireViolationOf(violation, SLUG_CONSTRAINT);
            throw new SlugTakenException();
        }

        outbox.append(AuditEvents.teamCreated(orgId, actorUserId, teamId.toString()));
        return TeamDto.of(team, 0);
    }

    @Transactional(readOnly = true)
    public PageResponse<TeamDto> list(String orgId, PageQuery pageQuery) {
        Page<Team> page = teams.findByOrgId(orgId, pageable(pageQuery, DEFAULT_TEAM_SORT, SORTABLE_TEAMS, "id"));
        Map<UUID, Long> counts = memberCounts(orgId, page.getContent());
        return PageResponse.of(page, team -> TeamDto.of(team, counts.getOrDefault(team.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public TeamDto get(String orgId, UUID teamId) {
        Team team = requireTeam(orgId, teamId);
        return TeamDto.of(team, teamMembers.countByOrgIdAndTeamId(orgId, teamId));
    }

    @Transactional
    public TeamDto rename(String orgId, String actorUserId, UUID teamId, String rawName) {
        guard.lockAsAtLeast(orgId, actorUserId, Role.ADMIN);
        Team team = requireTeam(orgId, teamId);

        String name = rawName.strip();
        if (!name.equals(team.getName())) {
            team.rename(name, clock.instant());
            teams.flush();
            outbox.append(AuditEvents.teamRenamed(orgId, actorUserId, teamId.toString()));
        }
        return TeamDto.of(team, teamMembers.countByOrgIdAndTeamId(orgId, teamId));
    }

    @Transactional
    public void delete(String orgId, String actorUserId, UUID teamId) {
        guard.lockAsAtLeast(orgId, actorUserId, Role.ADMIN);
        Team team = requireTeam(orgId, teamId);

        teams.delete(team);
        teams.flush();
        outbox.append(AuditEvents.teamDeleted(orgId, actorUserId, teamId.toString()));
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberDto> listMembers(String orgId, UUID teamId, PageQuery pageQuery) {
        requireTeam(orgId, teamId);
        Page<Membership> page = teamMembers.findActiveMembers(
                orgId, teamId, pageable(pageQuery, DEFAULT_MEMBER_SORT, SORTABLE_MEMBERS, "userId"));
        return PageResponse.of(page, MemberDto::of);
    }

    @Transactional
    public MemberDto addMember(String orgId, String actorUserId, UUID teamId, String userId) {
        guard.lockAsAtLeast(orgId, actorUserId, Role.ADMIN);
        requireTeam(orgId, teamId);
        Membership member = memberships
                .findByOrgIdAndUserIdAndStatus(orgId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(MemberNotFoundException::new);
        if (teamMembers.existsByOrgIdAndTeamIdAndUserId(orgId, teamId, userId)) {
            throw new AlreadyInTeamException();
        }

        teamMembers.saveAndFlush(new TeamMember(teamId, userId, orgId, actorUserId, clock.instant()));
        outbox.append(AuditEvents.teamMemberAdded(orgId, actorUserId, teamId.toString(), userId));
        return MemberDto.of(member);
    }

    @Transactional
    public void removeMember(String orgId, String actorUserId, UUID teamId, String userId) {
        guard.lockAsAtLeast(orgId, actorUserId, Role.ADMIN);
        requireTeam(orgId, teamId);
        if (teamMembers.deleteAssignment(orgId, teamId, userId) == 0) {
            throw new MemberNotFoundException();
        }
        outbox.append(AuditEvents.teamMemberRemoved(orgId, actorUserId, teamId.toString(), userId));
    }

    private Team requireTeam(String orgId, UUID teamId) {
        return teams.findByOrgIdAndId(orgId, teamId).orElseThrow(TeamNotFoundException::new);
    }

    private Map<UUID, Long> memberCounts(String orgId, Collection<Team> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = page.stream().map(Team::getId).toList();
        return teamMembers.countByTeams(orgId, ids).stream()
                .collect(Collectors.toMap(TeamMemberCount::teamId, TeamMemberCount::members));
    }

    private static Pageable pageable(PageQuery pageQuery, Sort defaultSort, Set<String> sortable, String tiebreaker) {
        return PageSorting.resolve(
                pageQuery, defaultSort, Sort.Order.asc(tiebreaker), order -> whitelisted(order, sortable));
    }

    private static Sort.Order whitelisted(Sort.Order order, Set<String> sortable) {
        if (!sortable.contains(order.getProperty())) {
            throw new InvalidSortException(order.getProperty());
        }
        return order.getProperty().equals("name") || order.getProperty().equals("displayName")
                ? order.ignoreCase()
                : order;
    }
}
