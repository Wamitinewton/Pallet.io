package io.pallet.gitintegration.retention;

import io.micrometer.core.instrument.Timer;
import io.pallet.common.inbox.InboxProperties;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.outbox.OutboxProperties;
import io.pallet.common.outbox.OutboxRepository;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.config.SchedulingLocks;
import io.pallet.gitintegration.config.SchedulingLocks.Job;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps every table bounded (ARCHITECTURE.md §Retention). Each sweep runs single-active under its own advisory lock, in
 * short transactions of at most {@code retention.batch-size} rows that each wait at most the outbox's
 * {@code lock-timeout} for a lock, until a batch comes back short or {@code retention.max-batches-per-run} is spent.
 */
@Component
class RetentionSweeps {

    enum Sweep {
        DELIVERY_PAYLOADS(Job.RETENTION_DELIVERY_PAYLOADS, "webhook_deliveries"),
        REPO_LINKS(Job.RETENTION_REPO_LINKS, "repo_links"),
        INSTALLATION_LINKS(Job.RETENTION_INSTALLATION_LINKS, "installation_links"),
        APPS(Job.RETENTION_APPS, "apps"),
        INSTALLATIONS(Job.RETENTION_INSTALLATIONS, "installations"),
        DELIVERIES(Job.RETENTION_DELIVERIES, "webhook_deliveries"),
        OUTBOX(Job.RETENTION_OUTBOX, "outbox_events"),
        INBOX(Job.RETENTION_INBOX, "processed_events"),
        AUTHORIZATION_STATES(Job.RETENTION_AUTHORIZATION_STATES, "authorization_states"),
        MANUAL_BUILD_REQUESTS(Job.RETENTION_MANUAL_BUILD_REQUESTS, "manual_build_requests"),
        CHECK_RUNS(Job.RETENTION_CHECK_RUNS, "check_runs"),
        DELETED_ORGS(Job.RETENTION_DELETED_ORGS, "deleted_orgs");

        private final Job job;
        private final String table;

        Sweep(Job job, String table) {
            this.job = job;
            this.table = table;
        }

        String table() {
            return table;
        }

        String tag() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        boolean nulls() {
            return this == DELIVERY_PAYLOADS;
        }
    }

    /**
     * Everything but payload nulling, referencing rows before the rows they reference, so one run can take a repo
     * link, then its installation link, then the installation.
     */
    static final List<Sweep> DAILY = List.of(
            Sweep.REPO_LINKS,
            Sweep.INSTALLATION_LINKS,
            Sweep.APPS,
            Sweep.INSTALLATIONS,
            Sweep.DELIVERIES,
            Sweep.OUTBOX,
            Sweep.INBOX,
            Sweep.AUTHORIZATION_STATES,
            Sweep.MANUAL_BUILD_REQUESTS,
            Sweep.CHECK_RUNS,
            Sweep.DELETED_ORGS);

    /** What one run did; {@code complete} is {@code false} when the batch budget or a lock timeout cut it short. */
    record Run(Sweep sweep, long rows, int batches, boolean complete) {}

    private static final Logger log = LoggerFactory.getLogger(RetentionSweeps.class);
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final SchedulingLocks locks;
    private final RetentionRepository repository;
    private final OutboxRepository outbox;
    private final TransactionalInbox inbox;
    private final RetentionMetrics metrics;
    private final TransactionTemplate transaction;
    private final GitIntegrationProperties.Retention retention;
    private final Duration outboxRetention;
    private final Duration inboxRetention;
    private final Duration lockTimeout;

    RetentionSweeps(
            SchedulingLocks locks,
            RetentionRepository repository,
            OutboxRepository outbox,
            TransactionalInbox inbox,
            RetentionMetrics metrics,
            PlatformTransactionManager transactionManager,
            GitIntegrationProperties properties,
            OutboxProperties outboxProperties,
            InboxProperties inboxProperties) {
        this.locks = locks;
        this.repository = repository;
        this.outbox = outbox;
        this.inbox = inbox;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.retention = properties.retention();
        this.outboxRetention = outboxProperties.retention();
        this.inboxRetention = inboxProperties.retention();
        this.lockTimeout = outboxProperties.lockTimeout();
    }

    /** @return the run, or empty when another instance is running this sweep */
    Optional<Run> run(Sweep sweep) {
        Timer.Sample sample = metrics.start();
        Run[] result = new Run[1];
        boolean ran = locks.runExclusively(sweep.job, () -> result[0] = sweep(sweep));
        if (!ran) {
            return Optional.empty();
        }
        metrics.ran(sweep, sample);
        return Optional.of(result[0]);
    }

    private Run sweep(Sweep sweep) {
        long rows = 0;
        int batches = 0;
        while (batches < retention.maxBatchesPerRun()) {
            Integer affected;
            try {
                affected = transaction.execute(status -> {
                    repository.applyLockTimeout(lockTimeout);
                    return batch(sweep, retention.batchSize());
                });
            } catch (DataAccessException e) {
                if (!lockTimedOut(e)) {
                    throw e;
                }
                log.warn(
                        "Retention sweep {} gave up waiting for a lock after {} batches, the next run continues: {}",
                        sweep.tag(),
                        batches,
                        e.getClass().getSimpleName());
                return new Run(sweep, rows, batches, false);
            }
            int count = affected == null ? 0 : affected;
            batches++;
            rows += count;
            metrics.rows(sweep, count);
            if (count < retention.batchSize()) {
                return new Run(sweep, rows, batches, true);
            }
        }
        return new Run(sweep, rows, batches, false);
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

    private int batch(Sweep sweep, int size) {
        return switch (sweep) {
            case DELIVERY_PAYLOADS -> repository.nullDeliveryPayloads(retention.deliveryPayload(), size);
            case REPO_LINKS -> repository.deleteRepoLinks(retention.terminalConnections(), size);
            case INSTALLATION_LINKS -> repository.deleteInstallationLinks(retention.terminalConnections(), size);
            case APPS -> repository.deleteApps(retention.deletedApps(), size);
            case INSTALLATIONS -> repository.deleteInstallations(retention.terminalConnections(), size);
            case DELIVERIES -> repository.deleteDeliveries(retention.deliveries(), size);
            case OUTBOX -> outbox.deletePublishedOlderThan(outboxRetention, size);
            case INBOX -> inbox.deleteProcessedOlderThan(inboxRetention, size);
            case AUTHORIZATION_STATES -> repository.deleteAuthorizationStates(retention.authorizationStates(), size);
            case MANUAL_BUILD_REQUESTS -> repository.deleteManualBuildRequests(retention.manualBuildRequests(), size);
            case CHECK_RUNS -> repository.deleteCheckRuns(retention.checkRuns(), size);
            case DELETED_ORGS -> repository.deleteDeletedOrgs(retention.deletedOrgs(), size);
        };
    }
}
