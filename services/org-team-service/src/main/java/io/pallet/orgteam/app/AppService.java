package io.pallet.orgteam.app;

import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.events.AppCreated;
import io.pallet.common.events.AppDeleted;
import io.pallet.orgteam.app.AppExceptions.AppNotFoundException;
import io.pallet.orgteam.app.AppExceptions.InvalidRegionException;
import io.pallet.orgteam.audit.AuditEvents;
import io.pallet.orgteam.config.ConstraintViolations;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.invite.InviteExceptions.QuotaExceededException;
import io.pallet.orgteam.member.MemberExceptions.InvalidSortException;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.security.OrgGuard;
import io.pallet.orgteam.support.PageSorting;
import io.pallet.orgteam.support.Slugs;
import io.pallet.orgteam.team.TeamExceptions.SlugTakenException;
import io.pallet.orgteam.team.TeamExceptions.TeamNotFoundException;
import io.pallet.orgteam.team.TeamRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppService {

    static final String SLUG_CONSTRAINT = "ux_apps_active_slug";

    private static final Set<String> SORTABLE = Set.of("name", "createdAt");
    private static final String TIEBREAKER = "id";
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc(TIEBREAKER));

    private final OrgGuard guard;
    private final TeamRepository teams;
    private final AppRepository apps;
    private final RegionCatalog regions;
    private final OutboxWriter outbox;
    private final OrgTeamMetrics metrics;
    private final OrgTeamProperties.Limits limits;
    private final Clock clock;

    AppService(
            OrgGuard guard,
            TeamRepository teams,
            AppRepository apps,
            RegionCatalog regions,
            OutboxWriter outbox,
            OrgTeamMetrics metrics,
            OrgTeamProperties properties,
            Clock clock) {
        this.guard = guard;
        this.teams = teams;
        this.apps = apps;
        this.regions = regions;
        this.outbox = outbox;
        this.metrics = metrics;
        this.limits = properties.limits();
        this.clock = clock;
    }

    @Transactional
    public AppDto create(String orgId, String actorUserId, CreateAppRequest request) {
        guard.lockAsAtLeast(orgId, actorUserId, Role.DEVELOPER);

        if (!regions.isValid(request.cloudProvider(), request.region())) {
            throw new InvalidRegionException(request.cloudProvider());
        }
        if (request.teamId() != null) {
            requireTeam(orgId, request.teamId());
        }

        String name = request.name().strip();
        String slug = request.slug() == null ? Slugs.fromName(name) : request.slug();
        if (apps.countByOrgIdAndStatus(orgId, AppStatus.ACTIVE) >= limits.maxAppsPerOrg()) {
            throw new QuotaExceededException("This organization has reached its app limit.");
        }
        if (apps.existsByOrgIdAndSlugAndStatus(orgId, slug, AppStatus.ACTIVE)) {
            throw new SlugTakenException();
        }

        UUID appUuid = UUID.randomUUID();
        App app = new App(
                appUuid,
                orgId,
                request.teamId(),
                name,
                slug,
                request.cloudProvider(),
                request.region(),
                clock.instant());
        try {
            apps.saveAndFlush(app);
        } catch (DataIntegrityViolationException violation) {
            ConstraintViolations.requireViolationOf(violation, SLUG_CONSTRAINT);
            throw new SlugTakenException();
        }

        String appId = appUuid.toString();
        outbox.append(AppCreated.of(
                orgId,
                appId,
                name,
                slug,
                request.teamId() == null ? null : request.teamId().toString(),
                app.getCloudProvider().name(),
                app.getRegion(),
                actorUserId));
        outbox.append(AuditEvents.appCreated(orgId, actorUserId, appId, slug, app.getCloudProvider(), app.getRegion()));
        metrics.appCreated();
        return AppDto.of(app);
    }

    @Transactional(readOnly = true)
    public PageResponse<AppDto> list(String orgId, UUID teamId, CloudProvider cloudProvider, PageQuery pageQuery) {
        return PageResponse.of(
                apps.search(
                        orgId,
                        teamId,
                        cloudProvider,
                        PageSorting.resolve(
                                pageQuery, DEFAULT_SORT, Sort.Order.asc(TIEBREAKER), AppService::whitelisted)),
                AppDto::of);
    }

    @Transactional(readOnly = true)
    public AppDto get(String orgId, UUID appId) {
        return AppDto.of(requireApp(orgId, appId));
    }

    @Transactional
    public AppDto update(String orgId, String actorUserId, UUID appId, UpdateAppRequest request) {
        guard.lockAsAtLeast(orgId, actorUserId, Role.DEVELOPER);
        App app = requireApp(orgId, appId);

        boolean changed = false;
        if (request.name() != null && !request.name().strip().equals(app.getName())) {
            app.rename(request.name().strip(), clock.instant());
            changed = true;
        }
        if (request.hasTeamId()) {
            UUID teamId = request.teamId();
            if (teamId != null) {
                requireTeam(orgId, teamId);
            }
            if (!Objects.equals(teamId, app.getTeamId())) {
                app.moveToTeam(teamId, clock.instant());
                changed = true;
            }
        }

        if (changed) {
            apps.flush();
            outbox.append(AuditEvents.appUpdated(orgId, actorUserId, appId.toString()));
        }
        return AppDto.of(app);
    }

    @Transactional
    public void delete(String orgId, String actorUserId, UUID appId) {
        guard.lockAsAtLeast(orgId, actorUserId, Role.ADMIN);
        App app = requireApp(orgId, appId);

        app.markDeleted(clock.instant());
        apps.flush();
        outbox.append(AppDeleted.of(orgId, appId.toString(), app.getSlug(), actorUserId));
        outbox.append(AuditEvents.appDeleted(orgId, actorUserId, appId.toString(), app.getSlug()));
    }

    private App requireApp(String orgId, UUID appId) {
        return apps.findByOrgIdAndIdAndStatus(orgId, appId, AppStatus.ACTIVE).orElseThrow(AppNotFoundException::new);
    }

    private void requireTeam(String orgId, UUID teamId) {
        if (!teams.existsByOrgIdAndId(orgId, teamId)) {
            throw new TeamNotFoundException();
        }
    }

    private static Sort.Order whitelisted(Sort.Order order) {
        if (!SORTABLE.contains(order.getProperty())) {
            throw new InvalidSortException(order.getProperty());
        }
        return order.getProperty().equals("name") ? order.ignoreCase() : order;
    }
}
