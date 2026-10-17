package io.pallet.buildqueue.api;

import io.pallet.buildqueue.api.WaitingOnResolver.Snapshot;
import io.pallet.buildqueue.api.dto.BuildDto;
import io.pallet.buildqueue.api.dto.BuildMapper;
import io.pallet.buildqueue.api.dto.QueueEntryDto;
import io.pallet.buildqueue.api.dto.QueueUsageDto;
import io.pallet.buildqueue.api.dto.QueueViewDto;
import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.config.BuildQueueProperties.Profile;
import io.pallet.buildqueue.dispatch.LimitProfiles;
import io.pallet.buildqueue.security.ResourceScope;
import io.pallet.common.api.PageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** The read side of the API. Nothing here returns another org's builds or counts. */
@Service
class QueueService {

    private static final Sort HISTORY_ORDER = Sort.by(Sort.Order.desc("enqueuedAt"), Sort.Order.desc("buildId"));

    private final BuildRepository builds;
    private final ResourceScope scope;
    private final LimitProfiles limits;
    private final BuildQueueProperties properties;

    QueueService(BuildRepository builds, ResourceScope scope, LimitProfiles limits, BuildQueueProperties properties) {
        this.builds = builds;
        this.scope = scope;
        this.limits = limits;
        this.properties = properties;
    }

    /**
     * In-flight builds first, then waiting ones in the dispatcher's within-org order. Repeatable read gives every
     * statement the same snapshot, so positions, reasons and usage agree with each other.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QueueViewDto queue(String orgId, Pageable page) {
        List<Build> inFlight = builds.findInFlight(orgId);
        long queued = builds.countQueued(orgId);
        Profile profile = limits.forOrg(orgId);
        Snapshot snapshot = new Snapshot(
                properties.dispatch().paused(),
                inFlight.stream().map(Build::appId).collect(Collectors.toSet()),
                inFlight.size(),
                profile.maxConcurrent(),
                builds.countInFlightOnPlatform(),
                properties.dispatch().globalConcurrency());

        List<QueueEntryDto> entries = new ArrayList<>(page.getPageSize());
        inFlight.stream()
                .skip(page.getOffset())
                .limit(page.getPageSize())
                .map(build -> QueueEntryDto.inFlight(BuildMapper.toDto(build)))
                .forEach(entries::add);

        int remaining = page.getPageSize() - entries.size();
        long queuedOffset = Math.max(0, page.getOffset() - inFlight.size());
        if (remaining > 0 && queuedOffset < queued) {
            List<Build> waiting = builds.findQueued(orgId, queuedOffset, remaining);
            for (int i = 0; i < waiting.size(); i++) {
                Build build = waiting.get(i);
                entries.add(QueueEntryDto.waiting(
                        BuildMapper.toDto(build),
                        Math.toIntExact(queuedOffset + i + 1),
                        WaitingOnResolver.resolve(build.appId(), snapshot)));
            }
        }

        QueueUsageDto usage = new QueueUsageDto(
                snapshot.orgInFlight(),
                profile.maxConcurrent(),
                queued,
                profile.maxQueued(),
                limits.profileName(orgId),
                snapshot.paused());
        Page<QueueEntryDto> entryPage = new PageImpl<>(entries, page, inFlight.size() + queued);
        return new QueueViewDto(usage, PageResponse.of(entryPage));
    }

    /** Newest first. A deleted app's history stays readable until retention removes it; no filter means every status. */
    @Transactional(readOnly = true)
    public PageResponse<BuildDto> history(String orgId, String appId, Set<BuildStatus> statuses, Pageable page) {
        scope.requireApp(orgId, appId);
        Pageable ordered = PageRequest.of(page.getPageNumber(), page.getPageSize(), HISTORY_ORDER);
        Page<Build> found = statuses.isEmpty()
                ? builds.findByOrgIdAndAppId(orgId, appId, ordered)
                : builds.findByOrgIdAndAppIdAndStatusIn(orgId, appId, statuses, ordered);
        return PageResponse.of(found, BuildMapper::toDto);
    }

    @Transactional(readOnly = true)
    public BuildDto build(String orgId, UUID buildId) {
        return BuildMapper.toDto(scope.requireBuild(orgId, buildId));
    }
}
