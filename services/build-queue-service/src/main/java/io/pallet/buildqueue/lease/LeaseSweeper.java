package io.pallet.buildqueue.lease;

import io.pallet.buildqueue.build.BuildEvents;
import io.pallet.buildqueue.build.BuildTransitions;
import io.pallet.buildqueue.build.DispatchedBuild;
import io.pallet.buildqueue.build.ExpiredBuild;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.config.SchedulingLocks;
import io.pallet.buildqueue.config.SchedulingLocks.Job;
import io.pallet.buildqueue.dispatch.DispatchSignal;
import io.pallet.buildqueue.observability.BuildSpans;
import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.common.outbox.OutboxWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes silence mean something (ARCHITECTURE.md §Leases and deadlines). A build that waited past {@code queue.max-age}
 * expires; one that missed its start deadline is redispatched as the next attempt until {@code dispatch.max-attempts},
 * then expires; one that outlived its run lease expires. Every instance sweeps; an advisory lock lets one of them act at
 * a time.
 *
 * <p>Start and run deadlines are acted on only while this instance can hear build-service. After an outage, the first
 * sweep that hears again moves every deadline due within {@code lease.grace} out by the grace, so results still being
 * caught up on aren't mistaken for silence. A paused dispatcher never burns attempts: start deadlines wait for it.
 *
 * <p>Each expiry and redispatch announces itself in a span continuing the trace its build was requested in.
 */
@Service
class LeaseSweeper {

    /** What a sweep did. {@code redispatchAttempts} holds the attempt number of each redispatch. */
    record Sweep(
            Outcome outcome,
            int queueExpired,
            int startExpired,
            int leaseExpired,
            List<Integer> redispatchAttempts,
            int deadlinesExtended) {

        enum Outcome {
            /** Another instance is sweeping. */
            LOCK_BUSY,
            /** The backbone was unreachable: only queue age was enforced. */
            DEAF,
            /** The first sweep after an outage: deadlines were extended, none acted on. */
            RECOVERED,
            SWEPT
        }

        static final Sweep LOCK_BUSY = new Sweep(Outcome.LOCK_BUSY, 0, 0, 0, List.of(), 0);

        static Sweep deaf(int queueExpired) {
            return new Sweep(Outcome.DEAF, queueExpired, 0, 0, List.of(), 0);
        }

        static Sweep recovered(int queueExpired, int deadlinesExtended) {
            return new Sweep(Outcome.RECOVERED, queueExpired, 0, 0, List.of(), deadlinesExtended);
        }

        static Sweep swept(int queueExpired, int startExpired, int leaseExpired, List<Integer> redispatchAttempts) {
            return new Sweep(Outcome.SWEPT, queueExpired, startExpired, leaseExpired, redispatchAttempts, 0);
        }

        Sweep {
            redispatchAttempts = List.copyOf(redispatchAttempts);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(LeaseSweeper.class);

    private final BuildQueueProperties properties;
    private final SchedulingLocks locks;
    private final BackboneReachability reachability;
    private final LeaseSweeperState state;
    private final BuildTransitions transitions;
    private final BuildEvents events;
    private final OutboxWriter outbox;
    private final DispatchSignal dispatchSignal;
    private final BuildSpans spans;
    private final QueueMetrics metrics;
    private final TransactionTemplate transaction;

    LeaseSweeper(
            BuildQueueProperties properties,
            SchedulingLocks locks,
            BackboneReachability reachability,
            LeaseSweeperState state,
            BuildTransitions transitions,
            BuildEvents events,
            OutboxWriter outbox,
            DispatchSignal dispatchSignal,
            BuildSpans spans,
            QueueMetrics metrics,
            TransactionTemplate transaction) {
        this.properties = properties;
        this.locks = locks;
        this.reachability = reachability;
        this.state = state;
        this.transitions = transitions;
        this.events = events;
        this.outbox = outbox;
        this.dispatchSignal = dispatchSignal;
        this.spans = spans;
        this.metrics = metrics;
        this.transaction = transaction;
    }

    /**
     * Asks whether the backbone can be heard before opening the transaction, so a slow broker never holds a connection
     * or row locks; then runs the sweep in one transaction, every transition committing with its event.
     */
    Sweep sweep() {
        boolean hear = reachability.canHear();
        Sweep sweep = transaction.execute(status -> lockedSweep(hear));
        if (sweep.outcome() == Sweep.Outcome.DEAF) {
            metrics.sweepSkipped();
        }
        return sweep;
    }

    private Sweep lockedSweep(boolean hear) {
        if (!locks.tryLock(Job.LEASE_SWEEP)) {
            return Sweep.LOCK_BUSY;
        }
        int batchSize = properties.lease().batchSize();
        int queueExpired = announce(transitions.expireQueued(properties.queue().maxAge(), batchSize));
        if (!hear) {
            if (state.markUnreachable()) {
                log.warn("Backbone unreachable; start and run deadlines are held until it can be heard again");
            }
            return Sweep.deaf(queueExpired);
        }
        Optional<Instant> unreachableSince = state.unreachableSince();
        if (unreachableSince.isPresent()) {
            Duration grace = properties.lease().grace();
            int extended = transitions.extendDeadlines(grace);
            state.markReachable();
            log.info(
                    "Backbone reachable again; unreachableSince={} deadlinesExtended={} grace={}",
                    unreachableSince.get(),
                    extended,
                    grace);
            return Sweep.recovered(queueExpired, extended);
        }
        int startExpired = 0;
        List<Integer> redispatchAttempts = List.of();
        if (!properties.dispatch().paused()) {
            BuildQueueProperties.Dispatch dispatch = properties.dispatch();
            startExpired = announce(transitions.expireUnstarted(dispatch.maxAttempts(), batchSize));
            redispatchAttempts =
                    transitions
                            .redispatch(dispatch.maxAttempts(), dispatch.startTimeout(), batchSize - startExpired)
                            .stream()
                            .map(this::announce)
                            .toList();
        }
        int leaseExpired = announce(transitions.expireLeases(batchSize));
        if (startExpired + leaseExpired > 0) {
            dispatchSignal.notifyDispatcher();
        }
        return Sweep.swept(queueExpired, startExpired, leaseExpired, redispatchAttempts);
    }

    private int announce(List<ExpiredBuild> expired) {
        for (ExpiredBuild build : expired) {
            spans.inTraceOf(
                    BuildSpans.EXPIRE,
                    build.buildId(),
                    build.traceparent(),
                    () -> outbox.append(events.expired(build)));
        }
        return expired.size();
    }

    private int announce(DispatchedBuild build) {
        spans.inTraceOf(
                BuildSpans.REDISPATCH,
                build.buildId(),
                build.traceparent(),
                () -> outbox.append(events.dispatched(build, properties.build().maxDuration())));
        return build.attempt();
    }
}
