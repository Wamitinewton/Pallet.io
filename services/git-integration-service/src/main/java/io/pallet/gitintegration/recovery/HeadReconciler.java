package io.pallet.gitintegration.recovery;

import io.pallet.common.error.ExternalServiceException;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.config.SchedulingLocks;
import io.pallet.gitintegration.config.SchedulingLocks.Job;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRateLimitedException;
import io.pallet.gitintegration.github.GitHubRateLimitGuard;
import io.pallet.gitintegration.installation.SyncCursorRepository;
import io.pallet.gitintegration.push.BranchHeadRepository;
import io.pallet.gitintegration.push.CompareLookup;
import io.pallet.gitintegration.push.PushProcessor;
import io.pallet.gitintegration.push.PushProcessor.Reconciled;
import io.pallet.gitintegration.recovery.ReconcileBatchPlanner.Candidate;
import io.pallet.gitintegration.recovery.ReconcileBatchPlanner.Cursor;
import io.pallet.gitintegration.recovery.ReconcileBatchPlanner.Pair;
import io.pallet.gitintegration.recovery.ReconcileBatchPlanner.Plan;
import io.pallet.gitintegration.recovery.ReconcileBatchPlanner.Turn;
import io.pallet.gitintegration.recovery.RecoveryMetrics.Checked;
import io.pallet.gitintegration.repolink.RepoLink;
import io.pallet.gitintegration.repolink.RepoLinkRepository;
import io.pallet.gitintegration.repolink.RepoLinkRepository.ReconcileCandidate;
import io.pallet.gitintegration.scm.ScmProvider;
import io.pallet.gitintegration.scm.ScmProvider.BranchHeadRead;
import io.pallet.gitintegration.scm.ScmProvider.CompareStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The backstop for pushes no webhook reported: every {@code reconciler.interval},
 * one instance reads GitHub's head of each linked branch, conditionally on the {@code ETag} stored with ours, and moves
 * every app on it forward through the same chain rule as a push. Each app is decided in its own transaction, and no
 * GitHub call is made inside one. An installation under its budget reserve, or one that hits its rate limit, is left
 * for a later run; GitHub being unavailable ends the run where it stands.
 */
@Component
class HeadReconciler {

    static final String CURSOR = "reconciler";

    /**
     * What one run did.
     *
     * @param checked repository branches fetched, by what they found
     * @param cursor where the next run starts
     */
    record Run(String runId, Map<Checked, Integer> checked, int pushesFound, Cursor cursor) {}

    private static final Logger log = LoggerFactory.getLogger(HeadReconciler.class);

    private final SchedulingLocks locks;
    private final RepoLinkRepository links;
    private final BranchHeadRepository heads;
    private final SyncCursorRepository cursors;
    private final PushProcessor pushes;
    private final ScmProvider scm;
    private final GitHubRateLimitGuard rateLimits;
    private final RecoveryMetrics metrics;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;
    private final Clock clock;
    private final GitIntegrationProperties.Reconciler properties;
    private final int maxLookupRounds;

    HeadReconciler(
            SchedulingLocks locks,
            RepoLinkRepository links,
            BranchHeadRepository heads,
            SyncCursorRepository cursors,
            PushProcessor pushes,
            ScmProvider scm,
            GitHubRateLimitGuard rateLimits,
            RecoveryMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            GitIntegrationProperties properties) {
        this.locks = locks;
        this.links = links;
        this.heads = heads;
        this.cursors = cursors;
        this.pushes = pushes;
        this.scm = scm;
        this.rateLimits = rateLimits;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
        this.properties = properties.reconciler();
        this.maxLookupRounds = properties.delivery().maxLookupRounds();
    }

    @Scheduled(fixedDelayString = "${pallet.git.reconciler.interval:PT15M}")
    void scheduled() {
        if (!properties.enabled()) {
            return;
        }
        try {
            run().ifPresentOrElse(
                            run -> log.info(
                                    "Head reconciler run finished runId={} checked={} pushesFound={} cursor={}",
                                    run.runId(),
                                    run.checked(),
                                    run.pushesFound(),
                                    run.cursor().format()),
                            () -> log.debug("Head reconciler run skipped, another instance holds the lock"));
        } catch (RuntimeException e) {
            log.warn("Head reconciler run failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** @return the run, or empty when another instance is running it */
    Optional<Run> run() {
        Instant started = clock.instant();
        Progress progress = new Progress(UUID.randomUUID());
        boolean ran = locks.runExclusively(Job.HEAD_RECONCILER, () -> reconcile(progress));
        if (!ran) {
            return Optional.empty();
        }
        metrics.reconcilerRan(Duration.between(started, clock.instant()));
        return Optional.of(new Run(
                "reconcile-run-" + progress.runNonce,
                Map.copyOf(progress.checked),
                progress.pushesFound,
                progress.cursor));
    }

    private void reconcile(Progress progress) {
        progress.cursor = Cursor.parse(cursors.find(CURSOR).orElse(null));
        Plan plan = plan(progress.cursor);
        try {
            for (Turn turn : plan.turns()) {
                progress.cursor = take(turn, progress);
            }
        } catch (GitHubUnavailable unavailable) {
            progress.cursor = unavailable.cursor;
            log.warn(
                    "Head reconciler run ended early, GitHub is unavailable: {}",
                    unavailable.getCause().getMessage());
        } finally {
            if (!plan.turns().isEmpty()) {
                cursors.save(CURSOR, progress.cursor.format());
            }
        }
    }

    private Plan plan(Cursor cursor) {
        return Objects.requireNonNull(readOnly.execute(status -> {
            try (Stream<ReconcileCandidate> rows =
                    links.streamReconcileCandidates(cursor.installationId(), cursor.resuming())) {
                return ReconcileBatchPlanner.plan(
                        rows.map(row -> new Candidate(
                                        row.getAppId(),
                                        row.getOrgId(),
                                        row.getInstallationId(),
                                        row.getRepoId(),
                                        row.getBranch()))
                                .iterator(),
                        cursor,
                        rateLimits::allowBackground,
                        properties.maxPerRun());
            }
        }));
    }

    /**
     * Fetches and applies one installation's pairs. A rate limit ends the turn, and the installation waits for the
     * next time the cursor comes round, as one under its reserve does.
     *
     * @return the cursor once the turn is over
     * @throws GitHubUnavailable carrying the cursor at the pair GitHub failed on
     */
    private Cursor take(Turn turn, Progress progress) {
        if (turn.skipped() || !rateLimits.allowBackground(turn.installationId())) {
            log.debug("Head reconciler skipped installationId={}, budget under reserve", turn.installationId());
            return turn.cursorAfter();
        }
        int done = 0;
        for (Pair pair : turn.pairs()) {
            try {
                reconcile(pair, progress);
            } catch (GitHubRateLimitedException limited) {
                log.info(
                        "Head reconciler stopped installationId={} for this run, rate limited until {}",
                        turn.installationId(),
                        limited.resetAt());
                return turn.cursorAfter();
            } catch (ExternalServiceException unavailable) {
                throw new GitHubUnavailable(turn.cursorBefore(done, progress.cursor), unavailable);
            } catch (RuntimeException e) {
                log.warn(
                        "Head reconciler could not check installationId={} repoId={}: {}: {}",
                        pair.installationId(),
                        pair.repoId(),
                        e.getClass().getSimpleName(),
                        e.getMessage());
            }
            done++;
        }
        return turn.cursorAt(done);
    }

    private void reconcile(Pair pair, Progress progress) {
        BranchHeadRead read = scm.branchHead(pair.installationId(), pair.repoId(), pair.branch(), sharedEtag(pair));
        if (read.notModified()) {
            checked(progress, Checked.NOT_MODIFIED);
            return;
        }
        if (read.missing()) {
            log.debug(
                    "Head reconciler found no branch for installationId={} repoId={}",
                    pair.installationId(),
                    pair.repoId());
            return;
        }
        Map<CompareLookup, CompareStatus> answers = new HashMap<>();
        boolean changed = false;
        for (Candidate link : pair.links()) {
            Reconciled.Outcome outcome = reconcile(pair, link, read, progress.runNonce, answers);
            if (outcome == Reconciled.Outcome.ACCEPTED) {
                metrics.pushFound();
                progress.pushesFound++;
            }
            changed |= outcome == Reconciled.Outcome.ACCEPTED || outcome == Reconciled.Outcome.ADOPTED;
        }
        checked(progress, changed ? Checked.CHANGED : Checked.UNCHANGED);
    }

    /**
     * One app, in its own transaction, re-read under it since the plan was made outside. A compare the chain rule asks
     * for is answered with nothing held, and shared by every app on the pair whose head is the same.
     */
    private Reconciled.Outcome reconcile(
            Pair pair,
            Candidate candidate,
            BranchHeadRead read,
            UUID runNonce,
            Map<CompareLookup, CompareStatus> answers) {
        for (int round = 0; ; round++) {
            Reconciled reconciled = Objects.requireNonNull(transaction.execute(status -> links.findReconcileTarget(
                            candidate.orgId(), candidate.appId())
                    .filter(link -> sameTarget(link, pair))
                    .map(link -> pushes.reconcile(
                            link,
                            read.sha(),
                            read.etag(),
                            runNonce,
                            lookup -> Optional.ofNullable(answers.get(lookup))))
                    .orElseGet(() -> new Reconciled(Reconciled.Outcome.UNLINKED, Optional.empty()))));
            if (reconciled.outcome() != Reconciled.Outcome.NEEDS_COMPARE) {
                return reconciled.outcome();
            }
            if (round == maxLookupRounds) {
                throw new IllegalStateException("GitHub compares did not settle within " + round + " rounds");
            }
            CompareLookup lookup = reconciled.compare().orElseThrow();
            answers.put(lookup, lookup.perform(scm));
        }
    }

    /**
     * The {@code ETag} to read the pair with: only one every app on it shares, since a {@code 304} then means GitHub's
     * head is still what each of their heads was read as. An app with no head needs GitHub's SHA to adopt.
     */
    private String sharedEtag(Pair pair) {
        List<UUID> appIds = pair.links().stream().map(Candidate::appId).toList();
        List<String> etags = heads.findEtags(appIds, pair.branch());
        if (etags.size() != appIds.size()) {
            return null;
        }
        Set<String> distinct = new HashSet<>(etags);
        return distinct.size() == 1 ? distinct.iterator().next() : null;
    }

    private void checked(Progress progress, Checked result) {
        metrics.checked(result);
        progress.checked(result);
    }

    private static boolean sameTarget(RepoLink link, Pair pair) {
        return link.installationId() == pair.installationId()
                && link.repoId() == pair.repoId()
                && link.productionBranch().equals(pair.branch());
    }

    private static final class Progress {

        private final UUID runNonce;
        private final Map<Checked, Integer> checked = new EnumMap<>(Checked.class);
        private int pushesFound;
        private Cursor cursor = Cursor.START;

        private Progress(UUID runNonce) {
            this.runNonce = runNonce;
        }

        void checked(Checked result) {
            checked.merge(result, 1, Integer::sum);
        }
    }

    private static final class GitHubUnavailable extends RuntimeException {

        private final transient Cursor cursor;

        private GitHubUnavailable(Cursor cursor, ExternalServiceException cause) {
            super(cause.getMessage(), cause, false, false);
            this.cursor = cursor;
        }
    }
}
