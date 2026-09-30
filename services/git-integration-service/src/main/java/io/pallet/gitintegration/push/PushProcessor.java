package io.pallet.gitintegration.push;

import io.pallet.common.events.GitPushReceived;
import io.pallet.common.outbox.OutboxWriter;
import io.pallet.gitintegration.delivery.DeliveryContext.Lookups;
import io.pallet.gitintegration.delivery.DeliveryOutcome;
import io.pallet.gitintegration.delivery.NeedsGitHub;
import io.pallet.gitintegration.delivery.payload.PushPayload;
import io.pallet.gitintegration.repolink.RepoLink;
import io.pallet.gitintegration.repolink.RepoLinkRepository;
import io.pallet.gitintegration.scm.ScmProvider.CompareStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Turns one push into a {@link GitPushReceived} per linked app that should build it (ARCHITECTURE.md §Push to event).
 * Runs inside the delivery's transaction: every app's head is locked in {@code app_id} order and decided under the
 * lock, and the events and the advanced heads commit with the delivery's outcome. When any app needs GitHub's compare
 * answer, every such question is asked at once through {@link NeedsGitHub}, and the whole push is decided again, from
 * freshly read heads, once the answers are in.
 *
 * <p>The same per-app step serves the head reconciler, through {@link #reconcile}.
 */
@Component
public class PushProcessor {

    public static final String PATH_UNCHANGED = "PATH_UNCHANGED";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String STALE = "STALE";
    public static final String NO_APP_ACCEPTED = "NO_APP_ACCEPTED";

    private final SkipRules skipRules;
    private final BranchHeadRepository heads;
    private final RepoLinkRepository links;
    private final PushEventFactory events;
    private final OutboxWriter outbox;
    private final PushMetrics metrics;

    PushProcessor(
            SkipRules skipRules,
            BranchHeadRepository heads,
            RepoLinkRepository links,
            PushEventFactory events,
            OutboxWriter outbox,
            PushMetrics metrics) {
        this.skipRules = skipRules;
        this.heads = heads;
        this.links = links;
        this.events = events;
        this.outbox = outbox;
        this.metrics = metrics;
    }

    /** @throws NeedsGitHub when an app's decision waits on a compare this round has no answer for */
    public DeliveryOutcome process(UUID deliveryId, Instant receivedAt, PushPayload push, Lookups lookups) {
        SkipRules.Result rules = skipRules.apply(push);
        if (rules instanceof SkipRules.Skip(String reason)) {
            metrics.skipped(reason);
            return DeliveryOutcome.ignored(reason);
        }
        SkipRules.Build build = (SkipRules.Build) rules;
        List<Verdict> verdicts = new ArrayList<>(build.links().size());
        Set<CompareLookup> unanswered = new LinkedHashSet<>();
        for (RepoLink link : build.links()) {
            Verdict verdict = decide(link, build.branch(), push, lookups);
            verdicts.add(verdict);
            verdict.compare().ifPresent(unanswered::add);
        }
        if (!unanswered.isEmpty()) {
            throw new NeedsGitHub(List.copyOf(unanswered));
        }
        return apply(deliveryId, receivedAt, build.branch(), push, verdicts);
    }

    /**
     * Moves one app's head toward {@code githubHead}, GitHub's current head of its branch, in the caller's transaction.
     * The rule runs with no {@code before}, so a head that differs from ours is placed only by GitHub's compare; an
     * accepted one publishes a {@code RECONCILED} push. A branch with no head adopts GitHub's without publishing:
     * changing a branch must not deploy, the next push does.
     *
     * @param etag the {@code ETag} of the read that returned {@code githubHead}, stored with the head it describes
     * @param answers compare answers the caller already has
     */
    public Reconciled reconcile(
            PushTarget link,
            String githubHead,
            String etag,
            UUID runNonce,
            Function<CompareLookup, Optional<CompareStatus>> answers) {
        String branch = link.productionBranch();
        String storedEtag = etag == null || etag.length() > BranchHead.MAX_ETAG_LENGTH ? null : etag;
        Optional<Step> locked = step(link, branch, null, githubHead, false, answers);
        if (locked.isEmpty()) {
            return Reconciled.of(Reconciled.Outcome.UNLINKED);
        }
        Step step = locked.get();
        if (step.head().isEmpty()) {
            heads.adopt(link.orgId(), link.appId(), branch, githubHead, storedEtag);
            return Reconciled.of(Reconciled.Outcome.ADOPTED);
        }
        String head = step.head().get();
        if (step.decision() != ChainDecision.NEEDS_COMPARE) {
            metrics.chainRule(step.decision());
        }
        return switch (step.decision()) {
            case NEEDS_COMPARE -> new Reconciled(Reconciled.Outcome.NEEDS_COMPARE, step.compare());
            case ACCEPT -> {
                outbox.append(events.reconciled(link, head, githubHead, runNonce));
                heads.advance(link.orgId(), link.appId(), branch, githubHead, storedEtag);
                metrics.published(GitPushReceived.TRIGGER_RECONCILED);
                yield Reconciled.of(Reconciled.Outcome.ACCEPTED);
            }
            case DUPLICATE -> {
                heads.recordEtag(link.orgId(), link.appId(), branch, githubHead, storedEtag);
                yield Reconciled.of(Reconciled.Outcome.UNCHANGED);
            }
            case STALE -> Reconciled.of(Reconciled.Outcome.STALE);
        };
    }

    private Verdict decide(RepoLink link, String branch, PushPayload push, Lookups lookups) {
        Optional<Step> locked = step(link, branch, push.before(), push.after(), push.forced(), lookups::answer);
        if (locked.isEmpty()) {
            return Verdict.unlinked(link);
        }
        Step step = locked.get();
        boolean touched = PathFilter.touches(push, link.rootDirectory());
        if (step.decision() == ChainDecision.NEEDS_COMPARE && touched) {
            return new Verdict(link, step.decision(), true, step.compare());
        }
        return new Verdict(link, step.decision(), touched, Optional.empty());
    }

    /**
     * Locks one app's head and runs the chain rule against it. A branch with no head yet is serialized on the repo
     * link's row instead, and read again once that lock is held, since a concurrent first push may have created it in
     * the meantime.
     *
     * @return empty when the link stopped being active
     */
    private Optional<Step> step(
            PushTarget link,
            String branch,
            String before,
            String after,
            boolean forced,
            Function<CompareLookup, Optional<CompareStatus>> answers) {
        Optional<String> head = heads.lockForUpdate(link.orgId(), link.appId(), branch);
        if (head.isEmpty()) {
            if (links.lockActive(link.orgId(), link.appId()).isEmpty()) {
                return Optional.empty();
            }
            head = heads.lockForUpdate(link.orgId(), link.appId(), branch);
        }
        Optional<CompareLookup> compare =
                head.map(sha -> new CompareLookup(link.installationId(), link.repoId(), sha, after));
        Optional<CompareStatus> answer = compare.flatMap(answers);
        ChainDecision decision = ChainRule.decide(head, before, after, forced, answer);
        return Optional.of(
                new Step(head, decision, decision == ChainDecision.NEEDS_COMPARE ? compare : Optional.empty()));
    }

    private DeliveryOutcome apply(
            UUID deliveryId, Instant receivedAt, String branch, PushPayload push, List<Verdict> verdicts) {
        boolean published = false;
        Set<String> reasons = new LinkedHashSet<>();
        for (Verdict verdict : verdicts) {
            RepoLink link = verdict.link();
            if (verdict.decision() != null && verdict.decision() != ChainDecision.NEEDS_COMPARE) {
                metrics.chainRule(verdict.decision());
            }
            Optional<String> skipped = verdict.skipReason();
            if (skipped.isEmpty()) {
                outbox.append(events.fromWebhook(link, push, deliveryId));
                metrics.published(GitPushReceived.TRIGGER_WEBHOOK, receivedAt);
                published = true;
            } else {
                metrics.skipped(skipped.get());
                reasons.add(skipped.get());
            }
            if (verdict.decision() == ChainDecision.ACCEPT) {
                heads.advance(link.orgId(), link.appId(), branch, push.after(), null);
            }
        }
        if (published) {
            return DeliveryOutcome.PROCESSED;
        }
        return DeliveryOutcome.ignored(reasons.size() == 1 ? reasons.iterator().next() : NO_APP_ACCEPTED);
    }

    /** @param compare the question the decision waits on, present only for {@code NEEDS_COMPARE} */
    private record Step(Optional<String> head, ChainDecision decision, Optional<CompareLookup> compare) {}

    /**
     * What a reconciler pass did to one app's head.
     *
     * @param compare the question to answer before passing again, present only for {@code NEEDS_COMPARE}
     */
    public record Reconciled(Outcome outcome, Optional<CompareLookup> compare) {

        public enum Outcome {
            ACCEPTED,
            ADOPTED,
            UNCHANGED,
            STALE,
            NEEDS_COMPARE,
            UNLINKED
        }

        static Reconciled of(Outcome outcome) {
            return new Reconciled(outcome, Optional.empty());
        }
    }

    /**
     * One app's decision. An app whose directory the push didn't touch still has its head advanced on a plain accept,
     * so the next push that does touch it is a fast-forward, but it never asks GitHub and never builds.
     *
     * @param decision null when the link stopped being active
     * @param compare the question this app waits on
     */
    private record Verdict(RepoLink link, ChainDecision decision, boolean touched, Optional<CompareLookup> compare) {

        static Verdict unlinked(RepoLink link) {
            return new Verdict(link, null, false, Optional.empty());
        }

        /** @return empty when the app builds this push */
        Optional<String> skipReason() {
            if (decision == null) {
                return Optional.of(SkipRules.NO_LINKED_APP);
            }
            return switch (decision) {
                case DUPLICATE -> Optional.of(DUPLICATE);
                case STALE -> Optional.of(STALE);
                case ACCEPT, NEEDS_COMPARE -> touched ? Optional.empty() : Optional.of(PATH_UNCHANGED);
            };
        }
    }
}
