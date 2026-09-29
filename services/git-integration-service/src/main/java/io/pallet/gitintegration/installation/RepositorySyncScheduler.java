package io.pallet.gitintegration.installation;

import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.config.SchedulingLocks;
import io.pallet.gitintegration.config.SchedulingLocks.Job;
import io.pallet.gitintegration.installation.RepositorySync.Result;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The backstop for lost repository webhooks: every {@code repository-sync.interval}, one instance syncs the least
 * recently synced {@code ACTIVE} installations, at most {@code repository-sync.max-per-run}. An installation under its
 * budget reserve is skipped by {@link RepositorySync}, and one that fails leaves the rest of the run going.
 */
@Component
class RepositorySyncScheduler {

    /** What one run did; {@code failed} counts installations whose sync threw. */
    record Run(String runId, Map<Result, Integer> results, int failed) {}

    private static final Logger log = LoggerFactory.getLogger(RepositorySyncScheduler.class);

    private final SchedulingLocks locks;
    private final InstallationRepository installations;
    private final RepositorySync sync;
    private final GitIntegrationProperties.RepositorySync properties;

    RepositorySyncScheduler(
            SchedulingLocks locks,
            InstallationRepository installations,
            RepositorySync sync,
            GitIntegrationProperties properties) {
        this.locks = locks;
        this.installations = installations;
        this.sync = sync;
        this.properties = properties.repositorySync();
    }

    @Scheduled(fixedDelayString = "${pallet.git.repository-sync.interval:P1D}")
    void scheduled() {
        if (!properties.periodic()) {
            return;
        }
        try {
            run().ifPresentOrElse(
                            run -> log.info(
                                    "Repository sync run finished runId={} results={} failed={}",
                                    run.runId(),
                                    run.results(),
                                    run.failed()),
                            () -> log.debug("Repository sync run skipped, another instance holds the lock"));
        } catch (RuntimeException e) {
            log.warn("Repository sync run failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** @return the run, or empty when another instance is running it */
    Optional<Run> run() {
        String runId = "sync-run-" + UUID.randomUUID();
        Map<Result, Integer> results = new EnumMap<>(Result.class);
        int[] failed = {0};
        boolean ran = locks.runExclusively(Job.REPOSITORY_SYNC, () -> {
            for (long installationId : installations.findDueForSync(properties.maxPerRun())) {
                try {
                    results.merge(sync.sync(installationId, runId), 1, Integer::sum);
                } catch (RuntimeException e) {
                    failed[0]++;
                    log.warn(
                            "Repository sync failed installationId={} runId={}: {}: {}",
                            installationId,
                            runId,
                            e.getClass().getSimpleName(),
                            e.getMessage());
                }
            }
        });
        return ran ? Optional.of(new Run(runId, Map.copyOf(results), failed[0])) : Optional.empty();
    }
}
