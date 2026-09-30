package io.pallet.gitintegration.delivery;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.delivery.DeliveryContext.Lookups;
import io.pallet.gitintegration.delivery.DeliveryRepository.RetryState;
import io.pallet.gitintegration.delivery.NeedsGitHub.Lookup;
import io.pallet.gitintegration.delivery.payload.DeliveryPayload;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRateLimitedException;
import io.pallet.gitintegration.observability.SpanAttributes;
import io.pallet.gitintegration.observability.Traceparents;
import io.pallet.gitintegration.scm.ScmProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns stored deliveries into effects. Every instance polls; {@code FOR
 * UPDATE SKIP LOCKED} keeps two workers off one delivery, and each delivery commits its effects, its outbox rows, and
 * its outcome in one transaction. No transaction or row lock is held while GitHub is called: a handler asks with
 * {@link NeedsGitHub}, and a lease on {@code next_attempt_at} keeps the delivery ours while the answer comes back.
 */
@Component
public class DeliveryProcessor implements AutoCloseable {

    public static final String NO_HANDLER = "NO_HANDLER";
    public static final String SPAN_NAME = "webhook delivery";

    static final String MDC_DELIVERY_ID = "deliveryId";
    static final String MDC_EVENT = "event";
    static final String MDC_INSTALLATION_ID = "installationId";
    static final String MDC_ATTEMPTS = "attempts";

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);
    private static final int MAX_ERROR_LENGTH = 500;
    private static final UUID NO_DELIVERY = new UUID(0, 0);
    private static final Pattern URL_QUERY = Pattern.compile("\\?\\S*");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);

    private final DeliveryRepository deliveries;
    private final PayloadParser parser;
    private final Map<String, DeliveryHandler> handlers;
    private final ScmProvider scm;
    private final DeliveryBackoff backoff;
    private final DeliveryMetrics metrics;
    private final TransactionTemplate transaction;
    private final ObjectProvider<Tracer> tracer;
    private final GitIntegrationProperties.Delivery properties;
    private final ExecutorService workers;

    DeliveryProcessor(
            DeliveryRepository deliveries,
            PayloadParser parser,
            ObjectProvider<DeliveryHandler> handlers,
            ScmProvider scm,
            DeliveryBackoff backoff,
            DeliveryMetrics metrics,
            PlatformTransactionManager transactionManager,
            ObjectProvider<Tracer> tracer,
            GitIntegrationProperties properties) {
        this.deliveries = deliveries;
        this.parser = parser;
        this.handlers = byEvent(handlers.orderedStream().toList());
        this.scm = scm;
        this.backoff = backoff;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.tracer = tracer;
        this.properties = properties.delivery();
        this.workers = this.properties.workers() > 1
                ? Executors.newFixedThreadPool(
                        this.properties.workers(),
                        Thread.ofPlatform().name("delivery-worker-", 0).daemon().factory())
                : null;
    }

    @Scheduled(fixedDelayString = "${pallet.git.delivery.poll-interval:PT0.25S}")
    void poll() {
        if (!properties.enabled()) {
            return;
        }
        try {
            processBatch();
        } catch (RuntimeException e) {
            log.warn("Delivery cycle ended early, retrying next poll: {}", describe(e));
        }
    }

    /**
     * Processes up to {@code batch-size} due deliveries, each at most once, on {@code workers} threads.
     *
     * @return how many deliveries this cycle claimed
     */
    public int processBatch() {
        Cycle cycle = new Cycle(properties.batchSize());
        if (workers == null) {
            cycle.drain();
            return cycle.claimed.get();
        }
        List<Future<?>> running = new ArrayList<>(properties.workers());
        for (int i = 0; i < properties.workers(); i++) {
            running.add(workers.submit(cycle::drain));
        }
        for (Future<?> worker : running) {
            try {
                worker.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                log.warn("Delivery worker ended early: {}", describe(e.getCause()));
            }
        }
        return cycle.claimed.get();
    }

    @Override
    public void close() throws InterruptedException {
        if (workers != null) {
            workers.shutdown();
            if (!workers.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
            }
        }
    }

    /** @return {@code false} once nothing is due */
    private boolean processNext(Cycle cycle) {
        try (Processing processing = new Processing()) {
            Round round = round(processing, () -> deliveries.claimNext(cycle.excluded()), Lookups.NONE);
            if (round instanceof Round.Empty) {
                return false;
            }
            cycle.seen.add(processing.deliveryId);
            int lookupRounds = 0;
            while (round instanceof Round.Failed(NeedsGitHub needs, Lookups known)) {
                if (lookupRounds == properties.maxLookupRounds()) {
                    recordFailure(
                            processing,
                            new IllegalStateException(
                                    "GitHub lookups did not settle within " + lookupRounds + " rounds"));
                    return true;
                }
                lookupRounds++;
                Optional<Lookups> answered = lookUp(processing, known, needs);
                if (answered.isEmpty()) {
                    return true;
                }
                round = round(processing, () -> deliveries.claim(processing.deliveryId), answered.get());
            }
            switch (round) {
                case Round.Completed(DeliveryOutcome outcome) -> {
                    metrics.completed(processing.event);
                    log.debug("Delivery completed: {}", outcome);
                }
                case Round.Failed failed -> recordFailure(processing, failed.cause());
                case Round.Empty() -> log.debug("Delivery finished by another worker during a GitHub lookup");
            }
            return true;
        }
    }

    private Round round(Processing processing, Supplier<Optional<WebhookDelivery>> claim, Lookups lookups) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                Optional<WebhookDelivery> claimed = claim.get();
                if (claimed.isEmpty()) {
                    return Round.EMPTY;
                }
                WebhookDelivery delivery = claimed.get();
                processing.claimed(delivery);
                DeliveryOutcome outcome = handle(delivery, lookups);
                switch (outcome) {
                    case DeliveryOutcome.Processed() -> deliveries.markProcessed(delivery.deliveryId());
                    case DeliveryOutcome.Ignored(String reason) ->
                        deliveries.markIgnored(delivery.deliveryId(), reason);
                }
                return new Round.Completed(outcome);
            }));
        } catch (RuntimeException e) {
            if (processing.deliveryId == null) {
                throw e;
            }
            return new Round.Failed(e, lookups);
        }
    }

    private DeliveryOutcome handle(WebhookDelivery delivery, Lookups lookups) {
        DeliveryPayload payload = parser.parse(delivery.event(), delivery.payload());
        DeliveryHandler handler = handlers.get(delivery.event());
        if (handler == null) {
            return DeliveryOutcome.ignored(NO_HANDLER);
        }
        DeliveryOutcome outcome = handler.handle(new DeliveryContext(
                delivery.deliveryId(),
                delivery.event(),
                delivery.action(),
                delivery.installationId(),
                delivery.receivedAt(),
                payload,
                lookups));
        return Objects.requireNonNull(outcome, () -> handler.getClass().getName() + " returned no outcome");
    }

    /**
     * Leases the delivery, then answers every lookup the round asked for with nothing locked.
     *
     * @return the answers so far, or empty when the delivery is no longer ours or a lookup failed (already recorded)
     */
    private Optional<Lookups> lookUp(Processing processing, Lookups known, NeedsGitHub needs) {
        List<Lookup<?>> pending = needs.lookups().stream()
                .filter(lookup -> !known.isAnswered(lookup))
                .distinct()
                .toList();
        Duration lease = properties.lease().multipliedBy(Math.max(1, pending.size()));
        Integer leased = transaction.execute(status -> deliveries.lease(processing.deliveryId, seconds(lease)));
        if (leased == null || leased == 0) {
            return Optional.empty();
        }
        Map<Lookup<?>, Object> answers = new HashMap<>();
        try {
            for (Lookup<?> lookup : pending) {
                Object answer = lookup.perform(scm);
                if (answer == null) {
                    throw new IllegalStateException(lookup.getClass().getSimpleName() + " returned no answer");
                }
                answers.put(lookup, answer);
            }
        } catch (RuntimeException e) {
            recordFailure(processing, e);
            return Optional.empty();
        }
        return Optional.of(known.with(answers));
    }

    /**
     * Records a failed round in its own short transaction, after the round's own rolled back. Skipped when another
     * worker already holds the delivery again: its outcome is the one that counts.
     */
    private void recordFailure(Processing processing, RuntimeException cause) {
        processing.failed(cause);
        String error = describe(cause);
        Recorded recorded = transaction.execute(status -> deliveries
                .lockForRetry(processing.deliveryId)
                .map(state -> record(processing.deliveryId, state, cause, error))
                .orElse(null));
        if (recorded == null) {
            log.debug("Delivery failure not recorded, another worker holds it: {}", error);
            return;
        }
        metrics.failed(recorded.kind());
        if (recorded.parked()) {
            log.error("Delivery parked: {}", error);
        } else {
            log.warn("Delivery will be retried ({}): {}", recorded.kind(), error);
        }
    }

    private Recorded record(UUID deliveryId, RetryState state, RuntimeException cause, String error) {
        return switch (cause) {
            case MalformedPayloadException malformed -> {
                deliveries.park(deliveryId, error);
                yield new Recorded(DeliveryMetrics.KIND_MALFORMED, true);
            }
            case GitHubRateLimitedException limited -> {
                deliveries.waitForRateLimit(
                        deliveryId, error, limited.resetAt(), seconds(backoff.jitter(properties.rateLimitJitter())));
                yield new Recorded(DeliveryMetrics.KIND_RATE_LIMITED, false);
            }
            case ExternalServiceException unavailable -> {
                Duration delay = backoff.next(state.getUnavailableStreak() + 1, properties.unavailableBackoffMax());
                deliveries.waitForGitHub(deliveryId, error, seconds(delay));
                yield new Recorded(DeliveryMetrics.KIND_GITHUB_UNAVAILABLE, false);
            }
            default -> {
                int attempts = state.getAttempts() + 1;
                if (attempts >= properties.maxAttempts()) {
                    deliveries.park(deliveryId, error);
                    yield new Recorded(DeliveryMetrics.KIND_ERROR, true);
                }
                deliveries.retryLater(deliveryId, error, seconds(backoff.next(attempts)));
                yield new Recorded(DeliveryMetrics.KIND_ERROR, false);
            }
        };
    }

    /** The exception's type and message, minus URL queries and control characters; never a payload value. */
    static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(typeName(failure));
        if (failure.getMessage() != null) {
            String message = URL_QUERY.matcher(failure.getMessage()).replaceAll("?…");
            text.append(": ").append(CONTROL.matcher(message).replaceAll(" "));
        }
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        if (root != failure) {
            text.append(" (caused by ").append(typeName(root)).append(')');
        }
        return text.length() <= MAX_ERROR_LENGTH ? text.toString() : text.substring(0, MAX_ERROR_LENGTH);
    }

    private static String typeName(Throwable failure) {
        String simple = failure.getClass().getSimpleName();
        return simple.isEmpty() ? failure.getClass().getName() : simple;
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }

    private static Map<String, DeliveryHandler> byEvent(List<DeliveryHandler> handlers) {
        Map<String, DeliveryHandler> byEvent = new HashMap<>();
        for (DeliveryHandler handler : handlers) {
            for (String event : handler.events()) {
                if (!SubscribedEvents.contains(event)) {
                    throw new IllegalStateException(handler.getClass().getName() + " handles " + event
                            + ", which the app doesn't subscribe to");
                }
                DeliveryHandler previous = byEvent.putIfAbsent(event, handler);
                if (previous != null) {
                    throw new IllegalStateException("Two handlers for " + event + ": "
                            + previous.getClass().getName() + " and "
                            + handler.getClass().getName());
                }
            }
        }
        return Map.copyOf(byEvent);
    }

    private final class Cycle {

        private final AtomicInteger remaining;
        private final AtomicInteger claimed = new AtomicInteger();
        private final Set<UUID> seen = ConcurrentHashMap.newKeySet();

        private Cycle(int batchSize) {
            this.remaining = new AtomicInteger(batchSize);
        }

        void drain() {
            while (remaining.getAndDecrement() > 0 && processNext(this)) {
                claimed.incrementAndGet();
            }
        }

        /** Deliveries this cycle already had, so a short backoff can't bring one back into the same cycle. */
        List<UUID> excluded() {
            List<UUID> excluded = new ArrayList<>(seen);
            excluded.add(NO_DELIVERY);
            return excluded;
        }
    }

    /** One delivery's trip through the processor: its identity, MDC, and span, across every round. */
    private final class Processing implements AutoCloseable {

        private UUID deliveryId;
        private String event;
        private Span span;
        private Tracer.SpanInScope scope;

        void claimed(WebhookDelivery delivery) {
            if (deliveryId == null) {
                deliveryId = delivery.deliveryId();
                event = delivery.event();
                MDC.put(MDC_DELIVERY_ID, deliveryId.toString());
                MDC.put(MDC_EVENT, SubscribedEvents.metricLabel(event));
                if (delivery.installationId() != null) {
                    MDC.put(MDC_INSTALLATION_ID, delivery.installationId().toString());
                }
                startSpan(delivery);
            }
            MDC.put(MDC_ATTEMPTS, String.valueOf(delivery.attempts()));
        }

        void failed(Throwable cause) {
            if (span != null) {
                span.error(cause);
            }
        }

        private void startSpan(WebhookDelivery delivery) {
            Tracer available = tracer.getIfAvailable();
            if (available == null) {
                return;
            }
            span = Traceparents.startChild(available, SPAN_NAME, delivery.traceparent())
                    .tag(SpanAttributes.EVENT, SubscribedEvents.metricLabel(delivery.event()))
                    .tag(SpanAttributes.DELIVERY_ID, delivery.deliveryId().toString());
            if (delivery.installationId() != null) {
                span.tag(
                        SpanAttributes.INSTALLATION_ID,
                        delivery.installationId().toString());
            }
            scope = available.withSpan(span);
        }

        @Override
        public void close() {
            if (scope != null) {
                scope.close();
            }
            if (span != null) {
                span.end();
            }
            MDC.remove(MDC_DELIVERY_ID);
            MDC.remove(MDC_EVENT);
            MDC.remove(MDC_INSTALLATION_ID);
            MDC.remove(MDC_ATTEMPTS);
        }
    }

    private record Recorded(String kind, boolean parked) {}

    private sealed interface Round {

        Round EMPTY = new Empty();

        record Empty() implements Round {}

        record Completed(DeliveryOutcome outcome) implements Round {}

        record Failed(RuntimeException cause, Lookups lookups) implements Round {}
    }
}
