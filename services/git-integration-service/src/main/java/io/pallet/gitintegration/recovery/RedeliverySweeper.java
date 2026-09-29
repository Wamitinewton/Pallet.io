package io.pallet.gitintegration.recovery;

import io.pallet.common.error.AppException;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.config.SchedulingLocks;
import io.pallet.gitintegration.config.SchedulingLocks.Job;
import io.pallet.gitintegration.delivery.DeliveryRepository;
import io.pallet.gitintegration.delivery.SubscribedEvents;
import io.pallet.gitintegration.github.GitHubClient;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRateLimitedException;
import io.pallet.gitintegration.github.HookDeliveryPage;
import io.pallet.gitintegration.github.dto.HookDelivery;
import io.pallet.gitintegration.installation.SyncCursorRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asks GitHub to redeliver the webhooks it failed to hand us. Every
 * {@code redelivery.interval}, one instance reads the app's delivery log back to just before its cursor and requests
 * each failed delivery whose GUID isn't stored; the redelivery then arrives through the webhook with that GUID. The
 * cursor only moves past entries that are handled or were requested, so a request GitHub refused for now is tried
 * again next run.
 */
@Component
class RedeliverySweeper {

    static final String CURSOR = "redelivery";

    /**
     * What one run did.
     *
     * @param requested redeliveries GitHub accepted
     * @param failed redelivery requests GitHub refused
     * @param cursor the cursor the run left, empty if it had none to save
     */
    record Run(String runId, int requested, int failed, Optional<Instant> cursor) {}

    private static final Logger log = LoggerFactory.getLogger(RedeliverySweeper.class);

    private final SchedulingLocks locks;
    private final GitHubClient github;
    private final DeliveryRepository deliveries;
    private final SyncCursorRepository cursors;
    private final RecoveryMetrics metrics;
    private final Clock clock;
    private final GitIntegrationProperties.Redelivery properties;

    /**
     * GUIDs requested within the last two intervals, so one still in flight isn't requested again. Losing it costs at
     * most a duplicate redelivery, which the delivery's primary key absorbs.
     */
    private final Map<String, Instant> inFlight = new ConcurrentHashMap<>();

    RedeliverySweeper(
            SchedulingLocks locks,
            GitHubClient github,
            DeliveryRepository deliveries,
            SyncCursorRepository cursors,
            RecoveryMetrics metrics,
            Clock clock,
            GitIntegrationProperties properties) {
        this.locks = locks;
        this.github = github;
        this.deliveries = deliveries;
        this.cursors = cursors;
        this.metrics = metrics;
        this.clock = clock;
        this.properties = properties.redelivery();
    }

    @Scheduled(fixedDelayString = "${pallet.git.redelivery.interval:PT5M}")
    void scheduled() {
        if (!properties.enabled()) {
            return;
        }
        try {
            run().ifPresentOrElse(
                            run -> log.info(
                                    "Redelivery sweep finished runId={} requested={} failed={}",
                                    run.runId(),
                                    run.requested(),
                                    run.failed()),
                            () -> log.debug("Redelivery sweep skipped, another instance holds the lock"));
        } catch (RuntimeException e) {
            log.warn("Redelivery sweep failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** @return the run, or empty when another instance is running it */
    Optional<Run> run() {
        Sweep sweep = new Sweep("redelivery-run-" + UUID.randomUUID());
        boolean ran = locks.runExclusively(Job.REDELIVERY_SWEEPER, () -> sweep(sweep));
        return ran ? Optional.of(new Run(sweep.runId, sweep.requested, sweep.failed, sweep.cursor)) : Optional.empty();
    }

    private void sweep(Sweep sweep) {
        Instant now = clock.instant();
        inFlight.values()
                .removeIf(requestedAt ->
                        requestedAt.isBefore(now.minus(properties.interval().multipliedBy(2))));
        Window window = read(floor(now));
        Set<String> resolved = handled(window.entries());
        for (List<HookDelivery> attempts : unresolved(window.entries(), resolved)) {
            if (sweep.requested + sweep.failed >= properties.maxPerRun()) {
                break;
            }
            HookDelivery newest = attempts.stream()
                    .max(Comparator.comparing(HookDelivery::deliveredAt).thenComparing(HookDelivery::id))
                    .orElseThrow();
            if (!request(newest, sweep)) {
                break;
            }
            resolved.add(newest.guid());
            inFlight.put(newest.guid(), now);
        }
        if (window.truncated()) {
            log.warn("Redelivery sweep read {} pages without reaching its cursor", properties.maxPages());
            return;
        }
        sweep.cursor = window.entries().stream()
                .filter(entry -> !resolved.contains(entry.guid()))
                .map(HookDelivery::deliveredAt)
                .min(Comparator.naturalOrder())
                .or(() ->
                        window.entries().stream().map(HookDelivery::deliveredAt).max(Comparator.naturalOrder()));
        sweep.cursor.ifPresent(cursor -> cursors.save(CURSOR, cursor.toString()));
    }

    /**
     * @return whether the run goes on: a rate limit or GitHub being unavailable ends it with the delivery unresolved,
     *     while a definite refusal (too old, unknown) resolves it, as asking again would get the same answer
     */
    private boolean request(HookDelivery delivery, Sweep sweep) {
        try {
            github.redeliver(delivery.id());
            metrics.redeliveryRequested();
            sweep.requested++;
            return true;
        } catch (GitHubRateLimitedException | ExternalServiceException e) {
            metrics.redeliveryFailed();
            sweep.failed++;
            log.warn(
                    "Redelivery sweep stopped, GitHub didn't take a request: {}",
                    e.getClass().getSimpleName());
            return false;
        } catch (AppException e) {
            metrics.redeliveryFailed();
            sweep.failed++;
            log.warn("GitHub refused to redeliver hookDeliveryId={}: {}", delivery.id(), e.getErrorCode());
            return true;
        }
    }

    private Instant floor(Instant now) {
        Instant lookback = now.minus(properties.lookback());
        return cursor().map(cursor -> cursor.minus(properties.overlap()))
                .filter(fromCursor -> fromCursor.isAfter(lookback))
                .orElse(lookback);
    }

    private Optional<Instant> cursor() {
        try {
            return cursors.find(CURSOR).map(Instant::parse);
        } catch (DateTimeParseException e) {
            log.warn("Ignoring an unreadable redelivery cursor");
            return Optional.empty();
        }
    }

    /** The log, newest first, back to the first entry older than {@code floor}, or {@code max-pages} pages. */
    private Window read(Instant floor) {
        List<HookDelivery> entries = new ArrayList<>();
        String next = null;
        for (int page = 0; page < properties.maxPages(); page++) {
            HookDeliveryPage deliveries = github.hookDeliveries(next, GitHubClient.MAX_PER_PAGE);
            boolean reachedFloor = false;
            for (HookDelivery delivery : deliveries.deliveries()) {
                if (delivery.deliveredAt().isBefore(floor)) {
                    reachedFloor = true;
                } else {
                    entries.add(delivery);
                }
            }
            next = deliveries.nextCursor();
            if (reachedFloor || next == null) {
                return new Window(entries, false);
            }
        }
        return new Window(entries, true);
    }

    /**
     * GUIDs that need nothing: one attempt succeeded, the delivery is stored, the app doesn't subscribe to its event,
     * the GUID can't be a delivery id at all, or a redelivery was asked for recently.
     */
    private Set<String> handled(List<HookDelivery> entries) {
        Set<String> handled = new HashSet<>();
        Map<String, UUID> candidates = new LinkedHashMap<>();
        for (HookDelivery entry : entries) {
            Optional<UUID> id = deliveryId(entry.guid());
            if (entry.succeeded()
                    || !SubscribedEvents.contains(entry.event())
                    || id.isEmpty()
                    || inFlight.containsKey(entry.guid())) {
                handled.add(entry.guid());
            } else {
                candidates.put(entry.guid(), id.get());
            }
        }
        candidates.keySet().removeAll(handled);
        if (!candidates.isEmpty()) {
            Set<UUID> stored = new HashSet<>(deliveries.findStoredIds(candidates.values()));
            candidates.forEach((guid, id) -> {
                if (stored.contains(id)) {
                    handled.add(guid);
                }
            });
        }
        return handled;
    }

    /** Each unresolved GUID's attempts, oldest GUID first, so the cursor can move up to whatever is left. */
    private static List<List<HookDelivery>> unresolved(List<HookDelivery> entries, Set<String> resolved) {
        Map<String, List<HookDelivery>> byGuid = new LinkedHashMap<>();
        entries.stream()
                .filter(entry -> !resolved.contains(entry.guid()))
                .sorted(Comparator.comparing(HookDelivery::deliveredAt).thenComparing(HookDelivery::id))
                .forEach(entry -> byGuid.computeIfAbsent(entry.guid(), guid -> new ArrayList<>())
                        .add(entry));
        return List.copyOf(byGuid.values());
    }

    private static Optional<UUID> deliveryId(String guid) {
        try {
            UUID id = UUID.fromString(guid);
            return id.toString().equalsIgnoreCase(guid) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private record Window(List<HookDelivery> entries, boolean truncated) {}

    private static final class Sweep {

        private final String runId;
        private int requested;
        private int failed;
        private Optional<Instant> cursor = Optional.empty();

        private Sweep(String runId) {
            this.runId = runId;
        }
    }
}
