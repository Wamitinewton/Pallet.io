package io.pallet.buildqueue.dispatch;

import io.micrometer.core.instrument.Timer;
import io.pallet.buildqueue.build.BuildEvents;
import io.pallet.buildqueue.build.BuildTransitions;
import io.pallet.buildqueue.build.DispatchedBuild;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.config.SchedulingLocks;
import io.pallet.buildqueue.config.SchedulingLocks.Job;
import io.pallet.buildqueue.dispatch.DispatchQuery.Candidate;
import io.pallet.buildqueue.observability.BuildSpans;
import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.buildqueue.observability.QueueMetrics.RoundResult;
import io.pallet.buildqueue.projection.OrgProjection;
import io.pallet.buildqueue.projection.OrgProjectionRepository;
import io.pallet.common.outbox.OutboxWriter;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gives waiting builds slots, one short transaction per round. Every instance runs rounds; a per-round advisory lock
 * lets exactly one of them dispatch at a time, and it is gone with the transaction. In-flight counts are read from
 * the table inside that transaction, never kept in memory.
 *
 * <p>Each dispatch runs in a span continuing the trace its build was requested in, so {@code build.dispatched} carries
 * that trace on to build-service, and the time the build waited for its slot is recorded as a span of its own.
 */
@Service
public class Dispatcher {

    /**
     * What a round did. {@code batchFull} means it stopped at {@code dispatch.batch-size} with capacity left, so another
     * round straight away may find more.
     */
    public record Round(Outcome outcome, int dispatched, boolean batchFull) {

        public enum Outcome {
            PAUSED(RoundResult.PAUSED),
            LOCK_BUSY(RoundResult.LOCK_BUSY),
            IDLE(RoundResult.IDLE),
            DISPATCHED(RoundResult.DISPATCHED);

            private final RoundResult result;

            Outcome(RoundResult result) {
                this.result = result;
            }

            RoundResult result() {
                return result;
            }
        }

        static final Round PAUSED = new Round(Outcome.PAUSED, 0, false);
        static final Round LOCK_BUSY = new Round(Outcome.LOCK_BUSY, 0, false);
        static final Round IDLE = new Round(Outcome.IDLE, 0, false);

        static Round dispatched(int dispatched, boolean batchFull) {
            return new Round(Outcome.DISPATCHED, dispatched, batchFull);
        }
    }

    private final BuildQueueProperties properties;
    private final SchedulingLocks locks;
    private final DispatchQuery query;
    private final OrgProjectionRepository orgs;
    private final BuildTransitions transitions;
    private final BuildEvents events;
    private final OutboxWriter outbox;
    private final BuildSpans spans;
    private final QueueMetrics metrics;
    private final TransactionTemplate transaction;

    Dispatcher(
            BuildQueueProperties properties,
            SchedulingLocks locks,
            DispatchQuery query,
            OrgProjectionRepository orgs,
            BuildTransitions transitions,
            BuildEvents events,
            OutboxWriter outbox,
            BuildSpans spans,
            QueueMetrics metrics,
            TransactionTemplate transaction) {
        this.properties = properties;
        this.locks = locks;
        this.query = query;
        this.orgs = orgs;
        this.transitions = transitions;
        this.events = events;
        this.outbox = outbox;
        this.spans = spans;
        this.metrics = metrics;
        this.transaction = transaction;
    }

    /**
     * Runs one round in its own transaction: every dispatch, its {@code build.dispatched} and the org's turn commit
     * together or not at all. A unique violation on {@code ux_builds_app_in_flight} is a bug and propagates, rolling
     * the whole round back.
     */
    public Round runRound() {
        Timer.Sample sample = metrics.startRound();
        Round round;
        try {
            round = properties.dispatch().paused() ? Round.PAUSED : transaction.execute(status -> lockedRound());
        } catch (RuntimeException e) {
            metrics.roundEnded(sample, RoundResult.ERROR);
            throw e;
        }
        metrics.roundEnded(sample, round.outcome().result());
        return round;
    }

    private Round lockedRound() {
        if (!locks.tryLock(Job.DISPATCH)) {
            return Round.LOCK_BUSY;
        }
        BuildQueueProperties.Dispatch settings = properties.dispatch();
        int free = settings.globalConcurrency() - query.countInFlight();
        int dispatched = 0;
        while (free > 0 && dispatched < settings.batchSize()) {
            int servedThisTurn = turn(Math.min(free, settings.batchSize() - dispatched), settings);
            if (servedThisTurn == 0) {
                break;
            }
            dispatched += servedThisTurn;
            free -= servedThisTurn;
        }
        if (dispatched == 0) {
            return Round.IDLE;
        }
        return Round.dispatched(dispatched, dispatched == settings.batchSize() && free > 0);
    }

    /**
     * One build for each of the first {@code limit} orgs in line. The query re-runs every turn, so an org served here
     * sorts behind every org still waiting for its first slot.
     */
    private int turn(int limit, BuildQueueProperties.Dispatch settings) {
        List<Candidate> candidates = query.next(limit);
        int served = 0;
        for (Candidate candidate : candidates) {
            if (dispatch(candidate, settings)) {
                served++;
            }
        }
        return served;
    }

    private boolean dispatch(Candidate candidate, BuildQueueProperties.Dispatch settings) {
        return spans.inTraceOf(
                BuildSpans.DISPATCH,
                candidate.buildId(),
                candidate.traceparent(),
                () -> dispatchInTrace(candidate, settings));
    }

    /** Org row, then build row: the lock order intake and cancellation follow too. */
    private boolean dispatchInTrace(Candidate candidate, BuildQueueProperties.Dispatch settings) {
        Optional<OrgProjection> org = orgs.lockByOrgId(candidate.orgId());
        if (org.isEmpty() || org.get().status() != OrgProjection.Status.ACTIVE) {
            return false;
        }
        Optional<DispatchedBuild> dispatched =
                transitions.dispatch(candidate.orgId(), candidate.buildId(), settings.startTimeout());
        if (dispatched.isEmpty()) {
            return false;
        }
        DispatchedBuild build = dispatched.get();
        outbox.append(events.dispatched(build, properties.build().maxDuration()));
        orgs.markServed(build.orgId());
        spans.recordWait(build.buildId(), build.traceparent(), build.enqueuedAt(), build.dispatchedAt());
        return true;
    }
}
