package io.pallet.gitintegration.checks;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.gitintegration.checks.CheckRunMetrics.DropReason;
import io.pallet.gitintegration.checks.CheckRunMetrics.FailureKind;
import io.pallet.gitintegration.checks.CheckRunMetrics.Reported;
import io.pallet.gitintegration.checks.CheckRunRepository.Claimed;
import io.pallet.gitintegration.checks.CheckRunRepository.Target;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.config.SchedulingLocks;
import io.pallet.gitintegration.config.SchedulingLocks.Job;
import io.pallet.gitintegration.delivery.DeliveryBackoff;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRateLimitedException;
import io.pallet.gitintegration.github.GitHubExceptions.WriteOutcomeUnknownException;
import io.pallet.gitintegration.github.GitHubRateLimitGuard;
import io.pallet.gitintegration.observability.SpanAttributes;
import io.pallet.gitintegration.observability.Traceparents;
import io.pallet.gitintegration.scm.ScmProvider;
import io.pallet.gitintegration.scm.ScmProvider.CheckRunReport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves each check run on GitHub toward what {@link DesiredStateWriter} recorded. One instance reports at a time, so
 * across the whole service an installation never has two check-run writes in flight (GitHub's secondary rate limits
 * punish concurrent writes); installations are written in parallel, each one's check runs one after another. Rows are
 * leased in a short transaction and written back in another; nothing is held while GitHub is called. A failure delays
 * a check mark and nothing else.
 */
@Component
class CheckRunReporter implements AutoCloseable {

    static final String NAME_PREFIX = "Pallet / ";
    static final String SPAN_NAME = "check run report";

    private static final Logger log = LoggerFactory.getLogger(CheckRunReporter.class);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);

    private enum Next {
        CONTINUE,
        STOP
    }

    private final SchedulingLocks locks;
    private final CheckRunRepository checkRuns;
    private final ScmProvider scm;
    private final GitHubRateLimitGuard rateLimits;
    private final DeliveryBackoff backoff;
    private final CheckRunMetrics metrics;
    private final TransactionTemplate transaction;
    private final GitIntegrationProperties.Checks properties;
    private final Duration rateLimitJitter;
    private final ExecutorService workers;
    private final ObjectProvider<Tracer> tracer;

    CheckRunReporter(
            SchedulingLocks locks,
            CheckRunRepository checkRuns,
            ScmProvider scm,
            GitHubRateLimitGuard rateLimits,
            DeliveryBackoff backoff,
            CheckRunMetrics metrics,
            PlatformTransactionManager transactionManager,
            GitIntegrationProperties properties,
            ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
        this.locks = locks;
        this.checkRuns = checkRuns;
        this.scm = scm;
        this.rateLimits = rateLimits;
        this.backoff = backoff;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties.checks();
        this.rateLimitJitter = properties.delivery().rateLimitJitter();
        this.workers = Executors.newFixedThreadPool(
                this.properties.parallelism(),
                Thread.ofPlatform().name("check-run-reporter-", 0).daemon().factory());
    }

    @Scheduled(fixedDelayString = "${pallet.git.checks.poll-interval:PT2S}")
    void poll() {
        if (!properties.enabled()) {
            return;
        }
        try {
            report();
        } catch (RuntimeException e) {
            log.warn("Check run report cycle failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** @return how many check runs this cycle claimed, or empty when another instance is reporting */
    Optional<Integer> report() {
        AtomicInteger claimed = new AtomicInteger();
        boolean ran = locks.runExclusively(Job.CHECK_RUN_REPORTER, () -> claimed.set(reportBatch()));
        return ran ? Optional.of(claimed.get()) : Optional.empty();
    }

    @Override
    public void close() throws InterruptedException {
        workers.shutdown();
        if (!workers.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            workers.shutdownNow();
        }
    }

    private int reportBatch() {
        List<Claimed> batch = Objects.requireNonNull(
                transaction.execute(status -> checkRuns.claimDue(properties.batchSize(), properties.lease())));
        Map<Long, List<Claimed>> byInstallation = new LinkedHashMap<>();
        for (Claimed claimed : batch) {
            if (claimed.target() == null) {
                abandon(claimed.checkRun(), DropReason.DISCONNECTED);
            } else {
                byInstallation
                        .computeIfAbsent(claimed.target().installationId(), id -> new ArrayList<>())
                        .add(claimed);
            }
        }
        List<Future<?>> running = new ArrayList<>(byInstallation.size());
        byInstallation.forEach(
                (installationId, rows) -> running.add(workers.submit(() -> reportInOrder(installationId, rows))));
        for (Future<?> installation : running) {
            try {
                installation.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                log.warn(
                        "Check run reporting for an installation ended early: {}",
                        e.getCause().getClass().getSimpleName());
            }
        }
        return batch.size();
    }

    /**
     * One installation's check runs, one write at a time. A spent budget or a rate limit reschedules the rest to the
     * reset; GitHub failing ends the installation's turn and leaves the rest on their lease.
     */
    private void reportInOrder(long installationId, List<Claimed> rows) {
        for (int i = 0; i < rows.size(); i++) {
            Optional<Instant> exhausted = rateLimits.exhaustedUntil(installationId);
            if (exhausted.isPresent()) {
                waitForReset(rows.subList(i, rows.size()), exhausted.get());
                return;
            }
            Claimed claimed = rows.get(i);
            try {
                reportOne(claimed.checkRun(), claimed.target());
            } catch (GitHubRateLimitedException limited) {
                metrics.failed(FailureKind.RATE_LIMITED);
                waitForReset(rows.subList(i, rows.size()), limited.resetAt());
                return;
            } catch (ExternalServiceException | WriteOutcomeUnknownException unavailable) {
                failed(claimed.checkRun(), FailureKind.GITHUB_UNAVAILABLE, unavailable);
                return;
            } catch (RuntimeException e) {
                failed(claimed.checkRun(), FailureKind.ERROR, e);
            }
        }
    }

    /**
     * Writes the claimed desire to GitHub. A check run with no stored id that was attempted before may exist from a
     * create whose answer was lost, so it is looked for before a second one is created.
     */
    private void reportOne(CheckRun row, Target target) {
        Tracer available = tracer.getIfAvailable();
        if (available == null) {
            write(row, target);
            return;
        }
        Span span = Traceparents.startChild(available, SPAN_NAME, row.traceparent())
                .tag(SpanAttributes.INSTALLATION_ID, String.valueOf(target.installationId()));
        try (Tracer.SpanInScope ignored = available.withSpan(span)) {
            write(row, target);
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void write(CheckRun row, Target target) {
        CheckRunReport report = report(row, target);
        Long checkRunId = row.checkRunId();
        Reported outcome = Reported.UPDATED;
        if (checkRunId == null && row.attempts() > 0) {
            checkRunId = scm.findCheckRun(target.installationId(), target.repoId(), report)
                    .orElse(null);
            outcome = Reported.RECOVERED;
        }
        if (checkRunId == null) {
            checkRunId = scm.createCheckRun(target.installationId(), target.repoId(), report);
            outcome = Reported.CREATED;
        } else {
            scm.updateCheckRun(target.installationId(), target.repoId(), checkRunId, report);
        }
        long reportedId = checkRunId;
        transaction.executeWithoutResult(status -> checkRuns.recordReported(
                row.orgId(),
                row.appId(),
                row.commitSha(),
                row.desiredRevision(),
                reportedId,
                row.desired().state()));
        metrics.reported(outcome);
        log.debug(
                "Check run reported orgId={} appId={} state={} outcome={}",
                row.orgId(),
                row.appId(),
                row.desired().state(),
                outcome);
    }

    private static CheckRunReport report(CheckRun row, Target target) {
        DesiredCheck desired = row.desired();
        return new CheckRunReport(
                NAME_PREFIX + target.appSlug(),
                row.commitSha(),
                row.appId().toString(),
                desired.state().wireName(),
                desired.conclusion() == null ? null : desired.conclusion().wireName(),
                desired.detailsUrl(),
                desired.title(),
                desired.summary());
    }

    private void failed(CheckRun row, FailureKind kind, RuntimeException cause) {
        metrics.failed(kind);
        int attempts = row.attempts() + 1;
        if (attempts >= properties.maxAttempts()) {
            abandon(row, DropReason.MAX_ATTEMPTS);
            log.warn(
                    "Check run given up after {} attempts orgId={} appId={}: {}",
                    attempts,
                    row.orgId(),
                    row.appId(),
                    cause.getClass().getSimpleName());
            return;
        }
        Duration delay = backoff.next(attempts);
        transaction.executeWithoutResult(status ->
                checkRuns.retryLater(row.orgId(), row.appId(), row.commitSha(), row.desiredRevision(), delay));
        log.info(
                "Check run will be retried orgId={} appId={} attempts={} kind={}: {}",
                row.orgId(),
                row.appId(),
                attempts,
                kind,
                cause.getClass().getSimpleName());
    }

    private void waitForReset(List<Claimed> rows, Instant resetAt) {
        transaction.executeWithoutResult(status -> rows.forEach(claimed -> checkRuns.waitForRateLimit(
                claimed.checkRun().orgId(),
                claimed.checkRun().appId(),
                claimed.checkRun().commitSha(),
                claimed.checkRun().desiredRevision(),
                resetAt,
                backoff.jitter(rateLimitJitter))));
    }

    private void abandon(CheckRun row, DropReason reason) {
        Integer abandoned = transaction.execute(
                status -> checkRuns.abandon(row.orgId(), row.appId(), row.commitSha(), row.desiredRevision()));
        if (abandoned != null && abandoned == 1) {
            metrics.dropped(reason);
        }
    }
}
