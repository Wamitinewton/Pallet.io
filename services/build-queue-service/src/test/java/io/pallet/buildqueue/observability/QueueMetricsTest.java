package io.pallet.buildqueue.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.buildqueue.build.PriorityClass;
import io.pallet.buildqueue.observability.QueueMetrics.Outcome;
import io.pallet.buildqueue.observability.QueueMetrics.Priority;
import io.pallet.buildqueue.observability.QueueMetrics.RequestOutcome;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.test.annotations.UnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@UnitTest
class QueueMetricsTest {

    private static final Instant ENQUEUED = Instant.parse("2026-10-16T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QueueMetrics metrics = new QueueMetrics(registry, Clock.systemUTC());

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @ParameterizedTest
    @EnumSource(PriorityClass.class)
    void everyPriorityClassHasTheMetricLabelOfTheSameRank(PriorityClass priorityClass) {
        assertThat(Priority.valueOf(priorityClass.name()).rank()).isEqualTo(priorityClass.rank());
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "2, 2", "3, 3+", "7, 3+"})
    void attemptsBeyondTheSecondShareOneLabel(int attempt, String tag) {
        assertThat(QueueMetrics.attemptTag(attempt)).isEqualTo(tag);
    }

    @Test
    void everySeriesExistsAtZeroBeforeItsFirstEvent() {
        assertThat(registry.find(QueueMetrics.FINISHED).counters())
                .hasSize(12)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(registry.find(QueueMetrics.REQUESTS).counters())
                .hasSize(RequestOutcome.values().length * QueueMetrics.TRIGGERS.size());
        assertThat(registry.find(QueueMetrics.DISPATCHED).counters()).hasSize(3);
    }

    @Test
    void aCountInsideATransactionWaitsForItsCommitAndARollbackDropsIt() {
        TransactionSynchronizationManager.initSynchronization();
        metrics.finished(Outcome.CANCELLED, BuildCancelled.REASON_USER_CANCELLED);

        assertThat(finished(Outcome.CANCELLED, BuildCancelled.REASON_USER_CANCELLED))
                .isZero();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(finished(Outcome.CANCELLED, BuildCancelled.REASON_USER_CANCELLED))
                .isOne();

        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        metrics.finished(Outcome.CANCELLED, BuildCancelled.REASON_USER_CANCELLED);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(finished(Outcome.CANCELLED, BuildCancelled.REASON_USER_CANCELLED))
                .isOne();
    }

    @Test
    void theFirstDispatchRecordsTheWaitFromStoredTimestampsAndARedispatchDoesNot() {
        metrics.dispatched(1, Priority.INTERACTIVE, ENQUEUED, ENQUEUED.plusSeconds(4));
        metrics.dispatched(2, Priority.INTERACTIVE, ENQUEUED, ENQUEUED.plusSeconds(4));

        var wait = registry.get(QueueMetrics.WAIT)
                .tag(QueueMetrics.TAG_PRIORITY_CLASS, "interactive")
                .timer();
        assertThat(wait.count()).isOne();
        assertThat(wait.totalTime(TimeUnit.SECONDS)).isEqualTo(4);
        assertThat(registry.get(QueueMetrics.DISPATCHED)
                        .tag(QueueMetrics.TAG_ATTEMPT, "2")
                        .counter()
                        .count())
                .isOne();
    }

    @Test
    void startLatencyIsMeasuredFromTheFirstDispatch() {
        metrics.started(ENQUEUED, ENQUEUED.plus(Duration.ofMillis(1500)));

        assertThat(registry.get(QueueMetrics.START_LATENCY).timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(1500);
    }

    @Test
    void aReasonAnOutcomeCannotCarryIsRefusedRatherThanBecomingALabel() {
        assertThatThrownBy(() -> metrics.finished(Outcome.SUCCEEDED, "build timed out after 30 minutes"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.requested(RequestOutcome.QUEUED, "SCHEDULED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private double finished(Outcome outcome, String reason) {
        return registry.get(QueueMetrics.FINISHED)
                .tag(QueueMetrics.TAG_OUTCOME, QueueMetrics.tag(outcome))
                .tag(QueueMetrics.TAG_REASON, reason)
                .counter()
                .count();
    }
}
