package io.pallet.buildqueue.retention;

import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.config.SchedulingLocks;
import io.pallet.buildqueue.config.SchedulingLocks.Job;
import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.buildqueue.observability.QueueMetrics.RetentionTable;
import io.pallet.buildqueue.retention.RetentionRepository.OrgPurge;
import io.pallet.common.inbox.InboxProperties;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.outbox.OutboxProperties;
import io.pallet.common.outbox.OutboxRepository;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the working set small and the history bounded (ARCHITECTURE.md §Retention). A run is single-active across
 * instances and walks the steps in {@link Step} order, referencing rows before the rows they reference, so one run can
 * take an app's last builds, then the app, then its org. Each step deletes in short transactions of at most
 * {@code retention.batch-size} rows, each waiting at most the outbox's {@code lock-timeout} for a lock, until a batch
 * comes back short or {@code retention.max-batches-per-run} is spent; the next run continues where it stopped.
 */
@Service
class RetentionSweeps {

    enum Step {
        TERMINAL_BUILDS(RetentionTable.BUILDS),
        APP_TOMBSTONES(RetentionTable.APPS),
        ORG_TOMBSTONES(RetentionTable.ORGS),
        OUTBOX(RetentionTable.OUTBOX_EVENTS),
        INBOX(RetentionTable.PROCESSED_EVENTS);

        private final RetentionTable table;

        Step(RetentionTable table) {
            this.table = table;
        }

        RetentionTable table() {
            return table;
        }
    }

    /**
     * What one step did. {@code deleted} counts rows of the step's own table; {@code complete} is false when the batch
     * budget, a lock timeout or a failure cut the step short.
     */
    record StepRun(Step step, long deleted, int batches, boolean complete) {}

    record Run(List<StepRun> steps) {

        Run {
            steps = List.copyOf(steps);
        }

        StepRun step(Step step) {
            return steps.stream().filter(run -> run.step() == step).findFirst().orElseThrow();
        }
    }

    /** One committed batch: rows of the step's table, plus the memberships an org batch took with it. */
    private record Batch(int rows, int memberships) {

        static Batch of(int rows) {
            return new Batch(rows, 0);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(RetentionSweeps.class);

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final SchedulingLocks locks;
    private final RetentionRepository repository;
    private final OutboxRepository outbox;
    private final TransactionalInbox inbox;
    private final QueueMetrics metrics;
    private final TransactionTemplate transaction;
    private final BuildQueueProperties.Retention retention;
    private final Duration outboxRetention;
    private final Duration inboxRetention;
    private final Duration lockTimeout;

    RetentionSweeps(
            SchedulingLocks locks,
            RetentionRepository repository,
            OutboxRepository outbox,
            TransactionalInbox inbox,
            QueueMetrics metrics,
            TransactionTemplate transaction,
            BuildQueueProperties properties,
            OutboxProperties outboxProperties,
            InboxProperties inboxProperties) {
        this.locks = locks;
        this.repository = repository;
        this.outbox = outbox;
        this.inbox = inbox;
        this.metrics = metrics;
        this.transaction = transaction;
        this.retention = properties.retention();
        this.outboxRetention = outboxProperties.retention();
        this.inboxRetention = inboxProperties.retention();
        this.lockTimeout = outboxProperties.lockTimeout();
    }

    /** @return the run, or empty when another instance is running retention */
    Optional<Run> run() {
        Optional<Run> run = locks.runExclusively(Job.RETENTION, this::sweep);
        run.ifPresentOrElse(
                RetentionSweeps::logRun, () -> log.debug("Retention skipped; another instance holds the lock"));
        return run;
    }

    private Run sweep() {
        List<StepRun> steps = new ArrayList<>();
        for (Step step : Step.values()) {
            steps.add(sweep(step));
        }
        return new Run(steps);
    }

    private StepRun sweep(Step step) {
        long deleted = 0;
        int batches = 0;
        while (batches < retention.maxBatchesPerRun()) {
            Batch batch;
            try {
                batch = Objects.requireNonNull(transaction.execute(status -> {
                    repository.applyLockTimeout(lockTimeout);
                    return batch(step, retention.batchSize());
                }));
            } catch (DataAccessException e) {
                if (lockTimedOut(e)) {
                    log.warn(
                            "Retention step {} gave up waiting {} for a lock after {} batches; the next run continues",
                            step,
                            lockTimeout,
                            batches);
                } else {
                    log.warn("Retention step {} failed after {} batches; the next run retries", step, batches, e);
                }
                return new StepRun(step, deleted, batches, false);
            }
            metrics.retentionDeleted(step.table(), batch.rows());
            metrics.retentionDeleted(RetentionTable.ORG_MEMBERSHIPS, batch.memberships());
            batches++;
            deleted += batch.rows();
            if (batch.rows() < retention.batchSize()) {
                return new StepRun(step, deleted, batches, true);
            }
        }
        return new StepRun(step, deleted, batches, false);
    }

    private Batch batch(Step step, int size) {
        return switch (step) {
            case TERMINAL_BUILDS -> Batch.of(repository.deleteTerminalBuilds(retention.terminalBuilds(), size));
            case APP_TOMBSTONES -> Batch.of(repository.deleteAppTombstones(retention.tombstones(), size));
            case ORG_TOMBSTONES -> {
                OrgPurge purge = repository.deleteOrgTombstones(retention.tombstones(), size);
                yield new Batch(purge.orgs(), purge.memberships());
            }
            case OUTBOX -> Batch.of(outbox.deletePublishedOlderThan(outboxRetention, size));
            case INBOX -> Batch.of(inbox.deleteProcessedOlderThan(inboxRetention, size));
        };
    }

    /** A Postgres lock timeout can arrive untranslated from {@code JdbcClient}, so its SQL state is checked too. */
    private static boolean lockTimedOut(DataAccessException failure) {
        if (failure instanceof PessimisticLockingFailureException) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void logRun(Run run) {
        log.info(
                "Retention run finished {}",
                run.steps().stream()
                        .map(step -> "%s={deleted=%d, batches=%d, complete=%s}"
                                .formatted(step.step(), step.deleted(), step.batches(), step.complete()))
                        .toList());
    }
}
