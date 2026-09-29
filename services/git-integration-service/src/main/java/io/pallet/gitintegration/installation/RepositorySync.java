package io.pallet.gitintegration.installation;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.github.GitHubClient;
import io.pallet.gitintegration.github.GitHubRateLimitGuard;
import io.pallet.gitintegration.installation.ConnectionLostNotifier.Account;
import io.pallet.gitintegration.installation.ConnectionLostNotifier.Reason;
import io.pallet.gitintegration.repolink.RepoLink.DisconnectReason;
import io.pallet.gitintegration.repolink.RepoLinkRepository;
import io.pallet.gitintegration.scm.ScmProvider;
import io.pallet.gitintegration.scm.ScmProvider.RepositoryListing;
import io.pallet.gitintegration.scm.ScmProvider.ScmRepository;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps {@code installation_repositories} equal to what the installation can reach on GitHub. The listing is fetched
 * with no transaction open and the first page's {@code ETag}, so an unchanged listing costs no budget; the replace is
 * one transaction under the installation's row lock. A complete listing is a definite answer, so a linked repository
 * missing from it is torn down and its orgs told, exactly as a lost {@code installation_repositories.removed} would
 * have done. Background work: it yields to user-facing calls once the installation's budget is under the reserve.
 */
@Component
public class RepositorySync implements AutoCloseable {

    public static final String RUNS = "git.repository_sync.runs";

    static final String CURSOR_PREFIX = "repo-sync:";

    private static final Logger log = LoggerFactory.getLogger(RepositorySync.class);
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);
    private static final String FAILED = "failed";
    private static final String DROPPED = "dropped";

    public enum Result {
        SYNCED,
        PARTIAL,
        UNCHANGED,
        SKIPPED_BUDGET,
        SKIPPED_INACTIVE
    }

    private final ScmProvider scm;
    private final GitHubRateLimitGuard rateLimits;
    private final InstallationRepository installations;
    private final InstallationRepositoryEntryRepository entries;
    private final SyncCursorRepository cursors;
    private final RepoLinkRepository repoLinks;
    private final ConnectionTeardown teardown;
    private final ConnectionLostNotifier notifier;
    private final TransactionTemplate transaction;
    private final MeterRegistry meters;
    private final int maxPages;
    private final ThreadPoolExecutor executor;
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();

    RepositorySync(
            ScmProvider scm,
            GitHubRateLimitGuard rateLimits,
            InstallationRepository installations,
            InstallationRepositoryEntryRepository entries,
            SyncCursorRepository cursors,
            RepoLinkRepository repoLinks,
            ConnectionTeardown teardown,
            ConnectionLostNotifier notifier,
            PlatformTransactionManager transactionManager,
            MeterRegistry meters,
            GitIntegrationProperties properties) {
        this.scm = scm;
        this.rateLimits = rateLimits;
        this.installations = installations;
        this.entries = entries;
        this.cursors = cursors;
        this.repoLinks = repoLinks;
        this.teardown = teardown;
        this.notifier = notifier;
        this.transaction = new TransactionTemplate(transactionManager);
        this.meters = meters;
        GitIntegrationProperties.RepositorySync sync = properties.repositorySync();
        this.maxPages = sync.maxPages();
        this.executor = new ThreadPoolExecutor(
                sync.workers(),
                sync.workers(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(sync.queueCapacity()),
                Thread.ofPlatform().name("repository-sync-", 0).daemon().factory());
    }

    /** Syncs once the current transaction commits, or now if there is none. Never fails the caller. */
    public void scheduleAfterCommit(long installationId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            schedule(installationId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                schedule(installationId);
            }
        });
    }

    public Result sync(long installationId) {
        return sync(installationId, "sync-" + UUID.randomUUID());
    }

    /** @param cause names this sync in the dedupe key of any notice it sends, so one cause never notifies twice */
    public Result sync(long installationId, String cause) {
        if (!rateLimits.allowBackground(installationId)) {
            return counted(Result.SKIPPED_BUDGET);
        }
        if (installations
                .findById(installationId)
                .filter(RepositorySync::isActive)
                .isEmpty()) {
            return counted(Result.SKIPPED_INACTIVE);
        }
        RepositoryListing first =
                scm.installationRepositories(installationId, 1, GitHubClient.MAX_PER_PAGE, cursor(installationId));
        if (first.notModified()) {
            transaction.executeWithoutResult(status -> installations.markRepositoriesSynced(installationId));
            return counted(Result.UNCHANGED);
        }
        Map<Long, ScmRepository> seen = new LinkedHashMap<>();
        RepositoryListing page = first;
        int pageNumber = 1;
        page.repositories().forEach(repository -> seen.put(repository.id(), repository));
        while (!isLast(page, pageNumber) && pageNumber < maxPages) {
            pageNumber++;
            page = scm.installationRepositories(installationId, pageNumber, GitHubClient.MAX_PER_PAGE, null);
            page.repositories().forEach(repository -> seen.put(repository.id(), repository));
        }
        boolean complete = isLast(page, pageNumber);
        return counted(Objects.requireNonNull(
                transaction.execute(status -> replace(installationId, seen, complete, first.etag(), cause))));
    }

    @Override
    public void close() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            executor.shutdownNow();
        }
    }

    /** A listing cut short by {@code max-pages} can't say what is absent, so it only upserts. */
    private Result replace(
            long installationId, Map<Long, ScmRepository> seen, boolean complete, String etag, String cause) {
        Optional<String> status = installations.lockStatus(installationId);
        if (status.isEmpty() || !Installation.Status.ACTIVE.name().equals(status.get())) {
            return Result.SKIPPED_INACTIVE;
        }
        for (ScmRepository repository : seen.values()) {
            entries.upsert(
                    installationId,
                    repository.id(),
                    repository.fullName(),
                    repository.defaultBranch(),
                    repository.isPrivate(),
                    repository.archived());
        }
        installations.markRepositoriesSynced(installationId);
        if (!complete) {
            return Result.PARTIAL;
        }
        tearDownVanished(installationId, seen.keySet(), cause);
        entries.deleteNotSyncedInThisTransaction(installationId);
        saveCursor(installationId, etag);
        return Result.SYNCED;
    }

    private void tearDownVanished(long installationId, Set<Long> listed, String cause) {
        Set<Long> vanished = new TreeSet<>(repoLinks.findActiveRepoIds(installationId));
        vanished.removeAll(listed);
        if (vanished.isEmpty()) {
            return;
        }
        TeardownResult result =
                teardown.repositoriesRemoved(installationId, vanished, DisconnectReason.REPOSITORY_ACCESS_REMOVED);
        installations
                .findById(installationId)
                .ifPresent(installation -> notifier.repositoriesLost(
                        cause, Account.of(installation), result, Reason.REPOSITORY_ACCESS_REMOVED));
        log.info(
                "Repository sync disconnected links to repositories the installation no longer reaches"
                        + " installationId={} repositories={} repoLinks={}",
                installationId,
                vanished.size(),
                result.disconnected().size());
    }

    private void schedule(long installationId) {
        if (!queued.add(installationId)) {
            return;
        }
        try {
            executor.execute(() -> {
                queued.remove(installationId);
                runQuietly(installationId);
            });
        } catch (RejectedExecutionException e) {
            queued.remove(installationId);
            meters.counter(RUNS, "outcome", DROPPED).increment();
            log.warn("Repository sync not queued, the queue is full installationId={}", installationId);
        }
    }

    private void runQuietly(long installationId) {
        try {
            sync(installationId);
        } catch (RuntimeException e) {
            meters.counter(RUNS, "outcome", FAILED).increment();
            log.warn(
                    "Repository sync failed installationId={}: {}: {}",
                    installationId,
                    e.getClass().getSimpleName(),
                    e.getMessage());
        }
    }

    private String cursor(long installationId) {
        return cursors.find(cursorName(installationId)).orElse(null);
    }

    private void saveCursor(long installationId, String etag) {
        String name = cursorName(installationId);
        if (etag == null || etag.length() > MAX_CURSOR_LENGTH) {
            cursors.delete(name);
        } else {
            cursors.save(name, etag);
        }
    }

    private static String cursorName(long installationId) {
        return CURSOR_PREFIX + installationId;
    }

    private Result counted(Result result) {
        meters.counter(RUNS, "outcome", result.name().toLowerCase(Locale.ROOT)).increment();
        return result;
    }

    private static boolean isActive(Installation installation) {
        return installation.status() == Installation.Status.ACTIVE;
    }

    private static boolean isLast(RepositoryListing page, int pageNumber) {
        return page.repositories().size() < GitHubClient.MAX_PER_PAGE
                || (long) pageNumber * GitHubClient.MAX_PER_PAGE >= page.totalCount();
    }
}
