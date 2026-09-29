package io.pallet.common.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes committed outbox rows in commit order per org (ADR-0017). Single active across replicas through a
 * transaction-scoped advisory lock; broker failures back the whole relay off, row failures retry and park only that
 * row and hold back only its org.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 500;
    private static final int MAX_BACKOFF_SHIFT = 30;
    private static final Pattern TRACEPARENT = Pattern.compile("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    private final OutboxRepository repository;
    private final OutboxEventTypes eventTypes;
    private final PlatformEventPublisher publisher;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transaction;
    private final TransactionTemplate rowTransaction;
    private final ObjectProvider<Tracer> tracer;
    private final OutboxProperties properties;
    private final Clock clock;
    private final OutboxMetrics metrics;

    private volatile Duration brokerBackoff = Duration.ZERO;
    private volatile Instant brokerRetryAt = Instant.MIN;

    OutboxRelay(
            OutboxRepository repository,
            OutboxEventTypes eventTypes,
            PlatformEventPublisher publisher,
            JsonMapper jsonMapper,
            PlatformTransactionManager transactionManager,
            OutboxProperties properties,
            Clock clock,
            OutboxMetrics metrics,
            ObjectProvider<Tracer> tracer) {
        this.repository = repository;
        this.eventTypes = eventTypes;
        this.publisher = publisher;
        this.jsonMapper = jsonMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.rowTransaction = new TransactionTemplate(transactionManager);
        // Each row's outcome commits on its own connection so a later failure cannot roll back an
        // event that already reached the broker.
        this.rowTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tracer = tracer;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${pallet.outbox.poll-interval:PT0.25S}")
    void tick() {
        if (clock.instant().isBefore(brokerRetryAt)) {
            return;
        }
        try {
            transaction.executeWithoutResult(status -> relayBatch());
        } catch (PessimisticLockingFailureException e) {
            metrics.relayActive(false);
            log.warn("Outbox relay gave up waiting for a lock, retrying next poll: {}", describe(e));
        } catch (RuntimeException e) {
            metrics.relayActive(false);
            throw e;
        }
    }

    Duration brokerBackoff() {
        return brokerBackoff;
    }

    private void relayBatch() {
        repository.applyLockTimeout(properties.lockTimeout());
        boolean holdsLock = repository.tryAcquireRelayLock(properties.advisoryLockKey());
        metrics.relayActive(holdsLock);
        if (!holdsLock) {
            return;
        }
        List<OutboxRow> batch = repository.findRelayBatch(properties.batchSize());
        Set<String> blockedOrgs = new HashSet<>();
        int published = 0;

        for (OutboxRow row : batch) {
            if (blockedOrgs.contains(row.orgId())) {
                continue;
            }
            PlatformEvent event;
            try {
                event = row.tombstone() ? null : rehydrate(row);
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
            rowTransaction.executeWithoutResult(status -> {
                repository.applyLockTimeout(properties.lockTimeout());
                repository.markPublished(row.id());
            });
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

    private void publish(OutboxRow row, PlatformEvent event) {
        Tracer available = tracer.getIfAvailable();
        TraceContext parent = available == null ? null : parentOf(available, row.traceparent());
        if (parent == null) {
            send(row, event);
            return;
        }
        Span span =
                available.spanBuilder().setParent(parent).name("outbox relay").start();
        try (Tracer.SpanInScope ignored = available.withSpan(span)) {
            send(row, event);
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void send(OutboxRow row, PlatformEvent event) {
        if (row.tombstone()) {
            publisher.publishTombstone(row.eventType(), row.recordKey());
        } else if (row.recordKey() != null) {
            publisher.publish(event, row.recordKey());
        } else {
            publisher.publish(event);
        }
    }

    private static TraceContext parentOf(Tracer tracer, String traceparent) {
        if (traceparent == null || !TRACEPARENT.matcher(traceparent).matches()) {
            return null;
        }
        String[] parts = traceparent.split("-");
        return tracer.traceContextBuilder()
                .traceId(parts[1])
                .spanId(parts[2])
                .sampled("01".equals(parts[3]))
                .build();
    }

    private PlatformEvent rehydrate(OutboxRow row) {
        if (row.payload() == null) {
            throw new IllegalStateException("Outbox row has no payload");
        }
        return jsonMapper.readValue(row.payload(), eventTypes.classFor(row.eventType()));
    }

    private void failRow(OutboxRow row, RuntimeException cause, Set<String> blockedOrgs) {
        blockedOrgs.add(row.orgId());
        String description = describe(cause);
        boolean parked = Boolean.TRUE.equals(rowTransaction.execute(status -> {
            repository.applyLockTimeout(properties.lockTimeout());
            return repository.recordFailure(
                    row.id(), properties.maxAttempts(), description, rowRetryDelay(row.attempts() + 1));
        }));
        metrics.publishFailure(OutboxMetrics.KIND_ROW);
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
        metrics.publishFailure(OutboxMetrics.KIND_BROKER);
        log.warn("Broker unavailable, relay backing off {}: {}", brokerBackoff, describe(cause));
    }

    private double rowRetryDelay(int attempts) {
        Duration delay = Duration.ofSeconds(1L << Math.min(attempts, MAX_BACKOFF_SHIFT));
        Duration capped = delay.compareTo(properties.rowBackoffMax()) > 0 ? properties.rowBackoffMax() : delay;
        return capped.toMillis() / 1000.0;
    }

    /** The publisher rejects a malformed row (a tombstone for a non-compacted topic) synchronously. */
    private static boolean isRowFault(Throwable failure) {
        if (failure instanceof IllegalArgumentException) {
            return true;
        }
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
