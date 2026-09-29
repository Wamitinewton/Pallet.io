package io.pallet.gitintegration.installation;

import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.outbox.OutboxWriter;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.config.SchedulingLocks;
import io.pallet.gitintegration.config.SchedulingLocks.Job;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRateLimitedException;
import io.pallet.gitintegration.installation.ConnectionLostNotifier.Account;
import io.pallet.gitintegration.installation.ConnectionLostNotifier.Reason;
import io.pallet.gitintegration.scm.ScmProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Uninstalls installations no org has linked for {@code unused-installation.grace} (ARCHITECTURE.md §Unused
 * installations). Every {@code unused-installation.sweep-interval}, one instance re-checks each due installation
 * without a lock, asks GitHub to uninstall it, and leaves the row for the {@code installation.deleted} webhook. GitHub
 * answering that it has no such installation marks the row {@code DELETED} here instead, so a lost webhook can't cause
 * a {@code DELETE} every day. A rate limit or GitHub failing ends the run; the next run retries.
 */
@Component
class UnusedInstallationSweep {

    enum Outcome {
        UNINSTALLED,
        ALREADY_GONE,
        STILL_IN_USE,
        FAILED
    }

    /** What one run did; {@code stoppedEarly} when GitHub was rate limited or unavailable. */
    record Run(String runId, Map<Outcome, Integer> outcomes, boolean stoppedEarly) {}

    static final String REASON_UNUSED = "UNUSED";

    private static final Logger log = LoggerFactory.getLogger(UnusedInstallationSweep.class);

    private final SchedulingLocks locks;
    private final InstallationRepository installations;
    private final InstallationLinkRepository links;
    private final ConnectionTeardown teardown;
    private final ConnectionLostNotifier notifier;
    private final ScmProvider scm;
    private final OutboxWriter outbox;
    private final UnusedInstallationMetrics metrics;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final GitIntegrationProperties.UnusedInstallation properties;

    UnusedInstallationSweep(
            SchedulingLocks locks,
            InstallationRepository installations,
            InstallationLinkRepository links,
            ConnectionTeardown teardown,
            ConnectionLostNotifier notifier,
            ScmProvider scm,
            OutboxWriter outbox,
            UnusedInstallationMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            GitIntegrationProperties properties) {
        this.locks = locks;
        this.installations = installations;
        this.links = links;
        this.teardown = teardown;
        this.notifier = notifier;
        this.scm = scm;
        this.outbox = outbox;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties.unusedInstallation();
    }

    @Scheduled(fixedDelayString = "${pallet.git.unused-installation.sweep-interval:P1D}")
    void scheduled() {
        if (!properties.enabled()) {
            return;
        }
        try {
            run().ifPresentOrElse(
                            run -> log.info(
                                    "Unused-installation sweep finished runId={} outcomes={} stoppedEarly={}",
                                    run.runId(),
                                    run.outcomes(),
                                    run.stoppedEarly()),
                            () -> log.debug("Unused-installation sweep skipped, another instance holds the lock"));
        } catch (RuntimeException e) {
            log.warn("Unused-installation sweep failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** @return the run, or empty when another instance is running it */
    Optional<Run> run() {
        String runId = "unused-sweep-" + UUID.randomUUID();
        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        boolean[] stoppedEarly = {false};
        boolean ran =
                locks.runExclusively(Job.UNUSED_INSTALLATION_SWEEP, () -> stoppedEarly[0] = !sweep(runId, outcomes));
        if (!ran) {
            return Optional.empty();
        }
        metrics.refresh();
        return Optional.of(new Run(runId, Map.copyOf(outcomes), stoppedEarly[0]));
    }

    /** @return {@code false} when GitHub cut the run short */
    private boolean sweep(String runId, Map<Outcome, Integer> outcomes) {
        double graceSeconds = properties.grace().toMillis() / 1000.0;
        List<Installation> due = installations.findUnusedFor(graceSeconds, properties.maxPerRun());
        for (Installation candidate : due) {
            long installationId = candidate.installationId();
            if (!stillUnused(candidate)) {
                outcomes.merge(Outcome.STILL_IN_USE, 1, Integer::sum);
                continue;
            }
            boolean uninstalled;
            try {
                uninstalled = scm.uninstall(installationId);
            } catch (GitHubRateLimitedException | ExternalServiceException e) {
                log.warn(
                        "Unused-installation sweep ended early runId={} installationId={}: {}",
                        runId,
                        installationId,
                        e.getClass().getSimpleName());
                return false;
            } catch (RuntimeException e) {
                outcomes.merge(Outcome.FAILED, 1, Integer::sum);
                log.warn(
                        "Unused-installation sweep could not uninstall installationId={} runId={}: {}: {}",
                        installationId,
                        runId,
                        e.getClass().getSimpleName(),
                        e.getMessage());
                continue;
            }
            if (uninstalled) {
                recordUninstalled(candidate);
                outcomes.merge(Outcome.UNINSTALLED, 1, Integer::sum);
            } else {
                recordAlreadyGone(candidate);
                outcomes.merge(Outcome.ALREADY_GONE, 1, Integer::sum);
            }
        }
        return true;
    }

    /** A link since the selection clears {@code unused_since}, and an unlink after that starts a different one. */
    private boolean stillUnused(Installation candidate) {
        long installationId = candidate.installationId();
        boolean unchanged = installations
                .findById(installationId)
                .filter(current -> current.status() != Installation.Status.DELETED)
                .filter(current -> candidate.unusedSince().equals(current.unusedSince()))
                .isPresent();
        return unchanged && links.findActiveOrgIds(installationId).isEmpty();
    }

    /** The row stays as it is until GitHub's {@code installation.deleted} webhook confirms the uninstall. */
    private void recordUninstalled(Installation installation) {
        long installationId = installation.installationId();
        Instant now = clock.instant();
        long daysUnused = Duration.between(installation.unusedSince(), now).toDays();
        transaction.executeWithoutResult(status -> links.findOrgIdsEverLinked(installationId)
                .forEach(orgId -> outbox.append(
                        AuditEvents.installationUninstalled(orgId, installationId, REASON_UNUSED, daysUnused, now))));
        metrics.uninstalled();
        log.info("Uninstalled unused installationId={} daysUnused={}", installationId, daysUnused);
    }

    /**
     * GitHub no longer has the installation: its webhook was lost, or it was uninstalled on GitHub. Any org that linked
     * it since the re-check loses that link and is told, as the webhook would have done.
     */
    private void recordAlreadyGone(Installation installation) {
        long installationId = installation.installationId();
        transaction.executeWithoutResult(status -> {
            TeardownResult result = teardown.installationDeleted(installationId);
            notifier.installationLost(
                    "unused-sweep-" + installationId + "-" + installation.unusedSince(),
                    Account.of(installation),
                    result.appsByOrg(),
                    Reason.INSTALLATION_DELETED);
        });
        log.info("Unused installationId={} was already gone from GitHub, marked it deleted", installationId);
    }
}
