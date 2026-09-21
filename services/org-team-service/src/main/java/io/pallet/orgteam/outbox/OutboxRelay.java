package io.pallet.orgteam.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.observability.MetricsCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(prefix = "pallet.orgteam.outbox", name = "enabled", matchIfMissing = true)
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 500;
    private static final int MAX_BACKOFF_SHIFT = 30;

    private final OutboxRepository repository;
    private final EventTypeRegistry registry;
    private final PlatformEventPublisher publisher;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transaction;
    private final TransactionTemplate rowTransaction;
    private final ObjectProvider<Tracer> tracer;
    private final OrgTeamProperties.Outbox properties;
    private final Clock clock;
    private final OutboxMetrics metrics;

    private volatile Duration brokerBackoff = Duration.ZERO;
    private volatile Instant brokerRetryAt = Instant.MIN;

    OutboxRelay(
            OutboxRepository repository,
            EventTypeRegistry registry,
            PlatformEventPublisher publisher,
            JsonMapper jsonMapper,
            PlatformTransactionManager transactionManager,
            OrgTeamProperties properties,
            Clock clock,
            OutboxMetrics metrics,
            ObjectProvider<Tracer> tracer) {
        this.repository = repository;
        this.registry = registry;
        this.publisher = publisher;
        this.jsonMapper = jsonMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.rowTransaction = new TransactionTemplate(transactionManager);
        // Each row's outcome commits on its own connection so a later failure cannot roll back an
        // event that already reached the broker.
        this.rowTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tracer = tracer;
        this.properties = properties.outbox();
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${pallet.orgteam.outbox.poll-interval:PT0.25S}")
    void tick() {
        if (clock.instant().isBefore(brokerRetryAt)) {
            return;
        }
        transaction.executeWithoutResult(status -> relayBatch());
    }

    Duration brokerBackoff() {
        return brokerBackoff;
    }

    private void relayBatch() {
        boolean holdsLock = repository.tryAcquireRelayLock(properties.advisoryLockKey());
        metrics.relayActive(holdsLock);
        if (!holdsLock) {
            return;
        }
        List<OutboxEvent> batch = repository.findRelayBatch(properties.batchSize());
        Set<String> blockedOrgs = new HashSet<>();
        int published = 0;

        for (OutboxEvent row : batch) {
            if (blockedOrgs.contains(row.orgId())) {
                continue;
            }
            PlatformEvent event;
            try {
                event = rehydrate(row);
            } catch (RuntimeException e) {
                failRow(row, e, blockedOrgs);
                continue;
            }
            try {
                publish(row, event);
            } catch (RuntimeException e) {
                if (!isRowFault(e)) {
                    if (published > 0) {
                        resetBrokerBackoff();
                    }
                    backOffBroker(e);
                    return;
                }
                failRow(row, e, blockedOrgs);
                continue;
            }
            rowTransaction.executeWithoutResult(status -> repository.markPublished(row.id()));
            metrics.published(row.eventType());
            published++;
        }
        if (published > 0) {
            resetBrokerBackoff();
        }
    }

    private void resetBrokerBackoff() {
        brokerBackoff = Duration.ZERO;
        brokerRetryAt = Instant.MIN;
    }

    private void publish(OutboxEvent row, PlatformEvent event) {
        Tracer available = tracer.getIfAvailable();
        TraceContext parent = available == null ? null : parentOf(available, row.traceparent());
        if (parent == null) {
            publisher.publish(event);
            return;
        }
        Span span =
                available.spanBuilder().setParent(parent).name("outbox relay").start();
        try (Tracer.SpanInScope ignored = available.withSpan(span)) {
            publisher.publish(event);
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private static TraceContext parentOf(Tracer tracer, String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-");
        if (parts.length != 4) {
            return null;
        }
        return tracer.traceContextBuilder()
                .traceId(parts[1])
                .spanId(parts[2])
                .sampled("01".equals(parts[3]))
                .build();
    }

    private PlatformEvent rehydrate(OutboxEvent row) {
        if (row.payload() == null) {
            throw new IllegalStateException("Outbox row has no payload");
        }
        return jsonMapper.readValue(row.payload(), registry.classFor(row.eventType()));
    }

    private void failRow(OutboxEvent row, RuntimeException cause, Set<String> blockedOrgs) {
        blockedOrgs.add(row.orgId());
        String description = describe(cause);
        boolean parked = Boolean.TRUE.equals(rowTransaction.execute(status -> repository.recordFailure(
                row.id(), properties.maxAttempts(), description, rowRetryDelay(row.attempts() + 1))));
        metrics.publishFailure(MetricsCatalog.KIND_ROW);
        if (parked) {
            log.error(
                    "Outbox row parked id={} eventId={} orgId={} type={} error={}",
                    row.id(),
                    row.eventId(),
                    row.orgId(),
                    row.eventType(),
                    description);
        } else {
            log.warn(
                    "Outbox row failed id={} eventId={} orgId={} type={} attempts={} error={}",
                    row.id(),
                    row.eventId(),
                    row.orgId(),
                    row.eventType(),
                    row.attempts() + 1,
                    description);
        }
    }

    private void backOffBroker(RuntimeException cause) {
        Duration next = brokerBackoff.isZero() ? properties.brokerBackoffInitial() : brokerBackoff.multipliedBy(2);
        brokerBackoff = next.compareTo(properties.brokerBackoffMax()) > 0 ? properties.brokerBackoffMax() : next;
        brokerRetryAt = clock.instant().plus(brokerBackoff);
        metrics.publishFailure(MetricsCatalog.KIND_BROKER);
        log.warn("Broker unavailable, relay backing off {}: {}", brokerBackoff, describe(cause));
    }

    private double rowRetryDelay(int attempts) {
        Duration delay = Duration.ofSeconds(1L << Math.min(attempts, MAX_BACKOFF_SHIFT));
        Duration capped = delay.compareTo(properties.rowBackoffMax()) > 0 ? properties.rowBackoffMax() : delay;
        return capped.toMillis() / 1000.0;
    }

    private static boolean isRowFault(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof RecordTooLargeException
                    || t instanceof SerializationException
                    || t instanceof InvalidTopicException) {
                return true;
            }
        }
        return false;
    }

    /** Exception messages can echo payload fragments, so only types are recorded. */
    private static String describe(Throwable failure) {
        String description = failure instanceof UnknownEventTypeException
                ? failure.getMessage()
                : failure.getClass().getSimpleName() + " caused by "
                        + rootCause(failure).getClass().getSimpleName();
        return description.length() > MAX_ERROR_LENGTH ? description.substring(0, MAX_ERROR_LENGTH) : description;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
