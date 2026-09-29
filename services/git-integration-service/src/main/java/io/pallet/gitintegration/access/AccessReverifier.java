package io.pallet.gitintegration.access;

import io.pallet.common.error.ExternalServiceException;
import io.pallet.gitintegration.access.ReverifyOutcome.Confirmed;
import io.pallet.gitintegration.access.ReverifyOutcome.Lost;
import io.pallet.gitintegration.access.ReverifyOutcome.Unknown;
import io.pallet.gitintegration.access.ReverifyPlanner.Batch;
import io.pallet.gitintegration.access.ReverifyPlanner.Candidate;
import io.pallet.gitintegration.access.ReverifyPlanner.Plan;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.config.SchedulingLocks;
import io.pallet.gitintegration.config.SchedulingLocks.Job;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRateLimitedException;
import io.pallet.gitintegration.github.GitHubRateLimitGuard;
import io.pallet.gitintegration.installation.ConnectionLostNotifier;
import io.pallet.gitintegration.installation.ConnectionLostNotifier.Account;
import io.pallet.gitintegration.installation.ConnectionTeardown;
import io.pallet.gitintegration.installation.Installation;
import io.pallet.gitintegration.installation.InstallationRepository;
import io.pallet.gitintegration.installation.TeardownResult;
import io.pallet.gitintegration.repolink.RepoLink;
import io.pallet.gitintegration.repolink.RepoLinkRepository;
import io.pallet.gitintegration.repolink.RepoLinkRepository.ReverifyCandidate;
import io.pallet.gitintegration.scm.ScmProvider;
import io.pallet.gitintegration.scm.ScmProvider.CollaboratorRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps checking, after a link exists, that its verifier still holds {@code link.min-repo-permission} on the
 * repository. Every {@code reverify.interval}, one instance asks GitHub with
 * the installation's own token, then applies each answer in a short transaction of its own, only if the link still has
 * the verifier and version it was checked against. A link is disconnected only on a definite answer; a rate limit
 * leaves the installation for the next run, and GitHub being unavailable ends the run.
 */
@Component
class AccessReverifier {

    /** GitHub's role for an account that no longer exists. */
    static final String NO_ROLE = ScmProvider.RepositoryRole.NONE.wireName();

    /**
     * What one run did.
     *
     * @param checked links asked about, by what GitHub's answer meant
     * @param stale answers dropped because the link changed while GitHub was being asked
     */
    record Run(String runId, Map<ReverifyOutcome.Kind, Integer> checked, int disconnected, int stale) {}

    private enum Applied {
        APPLIED,
        STALE
    }

    private static final Logger log = LoggerFactory.getLogger(AccessReverifier.class);

    private final SchedulingLocks locks;
    private final RepoLinkRepository links;
    private final InstallationRepository installations;
    private final ConnectionTeardown teardown;
    private final ConnectionLostNotifier notifier;
    private final ScmProvider scm;
    private final GitHubRateLimitGuard rateLimits;
    private final ReverifyMetrics metrics;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;
    private final Clock clock;
    private final GitIntegrationProperties.Reverify properties;
    private final RepoPermission minPermission;

    AccessReverifier(
            SchedulingLocks locks,
            RepoLinkRepository links,
            InstallationRepository installations,
            ConnectionTeardown teardown,
            ConnectionLostNotifier notifier,
            ScmProvider scm,
            GitHubRateLimitGuard rateLimits,
            ReverifyMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            GitIntegrationProperties properties) {
        this.locks = locks;
        this.links = links;
        this.installations = installations;
        this.teardown = teardown;
        this.notifier = notifier;
        this.scm = scm;
        this.rateLimits = rateLimits;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
        this.properties = properties.reverify();
        this.minPermission = properties.link().minRepoPermission();
    }

    @Scheduled(fixedDelayString = "${pallet.git.reverify.interval:P1D}")
    void scheduled() {
        if (!properties.enabled()) {
            return;
        }
        try {
            run().ifPresentOrElse(
                            run -> log.info(
                                    "Access re-verification run finished runId={} checked={} disconnected={} stale={}",
                                    run.runId(),
                                    run.checked(),
                                    run.disconnected(),
                                    run.stale()),
                            () -> log.debug("Access re-verification run skipped, another instance holds the lock"));
        } catch (RuntimeException e) {
            log.warn("Access re-verification run failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** @return the run, or empty when another instance is running it */
    Optional<Run> run() {
        Instant started = clock.instant();
        Progress progress = new Progress(UUID.randomUUID());
        boolean ran = locks.runExclusively(Job.ACCESS_REVERIFIER, () -> reverify(progress));
        if (!ran) {
            return Optional.empty();
        }
        metrics.ran(Duration.between(started, clock.instant()));
        metrics.refresh();
        return Optional.of(new Run(
                "reverify-run-" + progress.runNonce,
                Map.copyOf(progress.checked),
                progress.disconnected,
                progress.stale));
    }

    private void reverify(Progress progress) {
        Plan plan = plan();
        try {
            for (Batch batch : plan.batches()) {
                take(batch, progress);
            }
        } catch (GitHubUnavailable unavailable) {
            log.warn(
                    "Access re-verification run ended early, GitHub is unavailable: {}",
                    unavailable.getCause().getMessage());
        }
    }

    private Plan plan() {
        double minAgeSeconds = properties.minAge().toMillis() / 1000.0;
        return Objects.requireNonNull(readOnly.execute(status -> {
            try (Stream<ReverifyCandidate> rows = links.streamReverifyCandidates(minAgeSeconds)) {
                return ReverifyPlanner.plan(
                        rows.map(row -> new Candidate(
                                        row.getAppId(),
                                        row.getOrgId(),
                                        row.getInstallationId(),
                                        row.getRepoId(),
                                        row.getGithubUserId(),
                                        row.getGithubLogin(),
                                        row.getVersion()))
                                .iterator(),
                        rateLimits::allowBackground,
                        properties.maxPerRun());
            }
        }));
    }

    /**
     * Checks one installation's links in order. A rate limit, or the budget falling under the reserve, ends the
     * installation's turn; its remaining links stay the least recently checked, so the next run takes them first.
     *
     * @throws GitHubUnavailable when GitHub is failing, so no link is judged on a failure
     */
    private void take(Batch batch, Progress progress) {
        for (Candidate candidate : batch.links()) {
            if (!rateLimits.allowBackground(batch.installationId())) {
                log.debug(
                        "Access re-verification stopped installationId={}, budget under reserve",
                        batch.installationId());
                return;
            }
            ReverifyOutcome outcome;
            try {
                outcome = check(candidate);
            } catch (GitHubRateLimitedException limited) {
                counted(progress, new Unknown(Unknown.Reason.RATE_LIMITED));
                log.info(
                        "Access re-verification stopped installationId={} for this run, rate limited until {}",
                        batch.installationId(),
                        limited.resetAt());
                return;
            } catch (ExternalServiceException unavailable) {
                counted(progress, new Unknown(Unknown.Reason.GITHUB_UNAVAILABLE));
                throw new GitHubUnavailable(unavailable);
            } catch (RuntimeException e) {
                log.warn(
                        "Access re-verification could not check installationId={} appId={}: {}: {}",
                        candidate.installationId(),
                        candidate.appId(),
                        e.getClass().getSimpleName(),
                        e.getMessage());
                outcome = new Unknown(Unknown.Reason.ERROR);
            }
            counted(progress, outcome);
            apply(candidate, outcome, progress);
        }
    }

    /**
     * Asks GitHub for the verifier's role, following the verifier by account id: a login GitHub doesn't know, or one
     * that now answers for another account, is resolved through the id and asked about once more.
     */
    ReverifyOutcome check(Candidate candidate) {
        String login = candidate.githubLogin();
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<CollaboratorRole> answer =
                    scm.collaboratorRole(candidate.installationId(), candidate.repoId(), login);
            if (answer.isPresent() && answersFor(answer.get(), candidate)) {
                return decide(answer.get(), login);
            }
            Optional<String> current = scm.accountLogin(candidate.installationId(), candidate.githubUserId());
            if (current.isEmpty()) {
                return new Lost(login, NO_ROLE);
            }
            if (current.get().equalsIgnoreCase(login)) {
                return new Unknown(Unknown.Reason.REPOSITORY_NOT_FOUND);
            }
            login = current.get();
        }
        return new Unknown(Unknown.Reason.LOGIN_UNSETTLED);
    }

    private ReverifyOutcome decide(CollaboratorRole answer, String login) {
        return RepoPermission.fromWireName(answer.role().wireName())
                .filter(permission -> permission.atLeast(minPermission))
                .<ReverifyOutcome>map(permission -> new Confirmed(permission, login))
                .orElseGet(() -> new Lost(login, answer.role().wireName()));
    }

    private static boolean answersFor(CollaboratorRole answer, Candidate candidate) {
        return answer.accountId() == null || answer.accountId() == candidate.githubUserId();
    }

    private void apply(Candidate candidate, ReverifyOutcome outcome, Progress progress) {
        Applied applied =
                switch (outcome) {
                    case Confirmed confirmed -> confirm(candidate, confirmed);
                    case Lost lost -> disconnect(candidate, lost);
                    case Unknown ignored -> Applied.APPLIED;
                };
        if (applied == Applied.STALE) {
            progress.stale++;
            log.info(
                    "Access re-verification dropped a stale {} for appId={}, the link changed during the check",
                    outcome.kind(),
                    candidate.appId());
        } else if (outcome instanceof Lost) {
            progress.disconnected++;
            metrics.disconnected();
        }
    }

    private Applied confirm(Candidate candidate, Confirmed confirmed) {
        int recorded = Objects.requireNonNull(transaction.execute(status -> links.confirmAccess(
                candidate.orgId(),
                candidate.appId(),
                candidate.githubUserId(),
                candidate.version(),
                confirmed.permission().wireName(),
                confirmed.login())));
        return recorded == 1 ? Applied.APPLIED : Applied.STALE;
    }

    /** Locks the installation before the link, the order every teardown takes them in. */
    private Applied disconnect(Candidate candidate, Lost lost) {
        return Objects.requireNonNull(transaction.execute(status -> {
            if (installations.lockStatus(candidate.installationId()).isEmpty()) {
                return Applied.STALE;
            }
            Optional<RepoLink> locked =
                    links.lockActive(candidate.orgId(), candidate.appId()).filter(link -> unchanged(link, candidate));
            if (locked.isEmpty()) {
                return Applied.STALE;
            }
            Installation installation = installations
                    .findById(candidate.installationId())
                    .orElseThrow(() -> new IllegalStateException("A locked installation disappeared"));
            TeardownResult result = teardown.verifierAccessLost(locked.get(), lost.login(), lost.currentRole());
            notifier.repositoriesLost(
                    "reverify-" + candidate.appId() + "-v" + candidate.version(),
                    Account.of(installation),
                    result,
                    ConnectionLostNotifier.Reason.VERIFIER_ACCESS_LOST);
            return Applied.APPLIED;
        }));
    }

    private static boolean unchanged(RepoLink link, Candidate candidate) {
        return link.installationId() == candidate.installationId()
                && link.repoId() == candidate.repoId()
                && Objects.equals(link.verifiedGithubUserId(), candidate.githubUserId())
                && link.version() == candidate.version();
    }

    private void counted(Progress progress, ReverifyOutcome outcome) {
        metrics.checked(outcome.kind());
        progress.checked.merge(outcome.kind(), 1, Integer::sum);
    }

    private static final class Progress {

        private final UUID runNonce;
        private final Map<ReverifyOutcome.Kind, Integer> checked = new EnumMap<>(ReverifyOutcome.Kind.class);
        private int disconnected;
        private int stale;

        private Progress(UUID runNonce) {
            this.runNonce = runNonce;
        }
    }

    private static final class GitHubUnavailable extends RuntimeException {

        private GitHubUnavailable(ExternalServiceException cause) {
            super(cause.getMessage(), cause, false, false);
        }
    }
}
