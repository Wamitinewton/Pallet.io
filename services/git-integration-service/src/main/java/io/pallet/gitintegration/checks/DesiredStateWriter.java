package io.pallet.gitintegration.checks;

import io.micrometer.tracing.Tracer;
import io.pallet.gitintegration.checks.CheckRunMetrics.DropReason;
import io.pallet.gitintegration.checks.DesiredCheck.Conclusion;
import io.pallet.gitintegration.checks.DesiredCheck.Phase;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.observability.Traceparents;
import io.pallet.gitintegration.repolink.RepoLink;
import io.pallet.gitintegration.repolink.RepoLinkRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns build and deploy events into the check run GitHub should show, and records it for the reporter. Only ever moves
 * a check forward: a later state always wins, a same-state desire wins only from a later phase, and deploy updates
 * replace each other, which is how a completed check changes its conclusion when a deploy fails after its build
 * succeeded. Nothing here calls GitHub.
 */
@Component
public class DesiredStateWriter {

    static final int MAX_REASON_LENGTH = 500;
    static final int MAX_DETAILS_URL_LENGTH = 512;

    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}+");
    private static final Pattern MARKDOWN = Pattern.compile("[\\\\`*_{}\\[\\]()#+\\-.!|<>~&]");
    private static final Set<String> URL_SCHEMES = Set.of("https", "http");

    public enum Result {
        APPLIED,
        UNCHANGED,
        DROPPED
    }

    private final CheckRunRepository checkRuns;
    private final RepoLinkRepository links;
    private final CheckRunMetrics metrics;
    private final boolean awaitDeploy;
    private final ObjectProvider<Tracer> tracer;

    DesiredStateWriter(
            CheckRunRepository checkRuns,
            RepoLinkRepository links,
            CheckRunMetrics metrics,
            GitIntegrationProperties properties,
            ObjectProvider<Tracer> tracer) {
        this.checkRuns = checkRuns;
        this.links = links;
        this.metrics = metrics;
        this.tracer = tracer;
        this.awaitDeploy = properties.checks().awaitDeploy();
    }

    /**
     * Records {@code target} for the app's check run on the commit, unless it would move the check backwards. An app
     * the org has no active repo link for has nothing to report to and is dropped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result apply(String orgId, UUID appId, String commitSha, DesiredCheck target) {
        if (!links.existsByOrgIdAndAppIdAndStatus(orgId, appId, RepoLink.Status.ACTIVE)) {
            metrics.dropped(DropReason.UNLINKED);
            return Result.DROPPED;
        }
        String traceparent = Traceparents.current(tracer.getIfAvailable());
        if (checkRuns.insertIfAbsent(orgId, appId, commitSha, target, traceparent)) {
            metrics.desired(true);
            return Result.APPLIED;
        }
        Optional<CheckRun> current = checkRuns.lock(orgId, appId, commitSha);
        if (current.isEmpty()) {
            metrics.dropped(DropReason.UNLINKED);
            return Result.DROPPED;
        }
        Optional<DesiredCheck> merged = merge(current.get().desired(), target);
        merged.ifPresent(desired -> checkRuns.updateDesired(orgId, appId, commitSha, desired, traceparent));
        metrics.desired(merged.isPresent());
        return merged.isPresent() ? Result.APPLIED : Result.UNCHANGED;
    }

    DesiredCheck buildStarted() {
        return new DesiredCheck(CheckState.IN_PROGRESS, null, null, "Building", Phase.BUILD_STARTED);
    }

    DesiredCheck buildSucceeded() {
        return awaitDeploy
                ? new DesiredCheck(CheckState.IN_PROGRESS, null, null, "Built, deploying", Phase.BUILD_FINISHED)
                : new DesiredCheck(
                        CheckState.COMPLETED, Conclusion.SUCCESS, null, "Build succeeded", Phase.BUILD_FINISHED);
    }

    DesiredCheck buildFailed(String reason) {
        String summary = reason == null || reason.isBlank()
                ? "Build failed"
                : "Build failed: " + plainText(reason, MAX_REASON_LENGTH);
        return new DesiredCheck(CheckState.COMPLETED, Conclusion.FAILURE, null, summary, Phase.BUILD_FINISHED);
    }

    /** @param url the live URL, used as the check's details link only when it is an absolute http(s) URL */
    DesiredCheck deployStateChanged(String toState, String url) {
        Optional<DeployState> known = DeployState.fromWireName(toState);
        if (known.filter(DeployState::isLive).isPresent()) {
            return new DesiredCheck(
                    CheckState.COMPLETED, Conclusion.SUCCESS, detailsUrl(url), "Deployed", Phase.DEPLOY);
        }
        String state = plainText(toState.toLowerCase(Locale.ROOT).replace('_', ' '), MAX_REASON_LENGTH);
        if (known.filter(DeployState::isFailed).isPresent()) {
            return new DesiredCheck(
                    CheckState.COMPLETED, Conclusion.FAILURE, null, "Deploy failed: " + state, Phase.DEPLOY);
        }
        return new DesiredCheck(CheckState.IN_PROGRESS, null, null, "Deploying: " + state, Phase.DEPLOY);
    }

    /** @return what the check should show instead of {@code current}, or empty to leave it */
    static Optional<DesiredCheck> merge(DesiredCheck current, DesiredCheck target) {
        if (target.equals(current)) {
            return Optional.empty();
        }
        if (target.state().isAfter(current.state())) {
            return Optional.of(target);
        }
        if (target.state() != current.state()) {
            return Optional.empty();
        }
        boolean laterPhase = target.phase().compareTo(current.phase()) > 0;
        boolean deployUpdate = target.phase() == Phase.DEPLOY && current.phase() == Phase.DEPLOY;
        return laterPhase || deployUpdate ? Optional.of(target) : Optional.empty();
    }

    /**
     * One line of at most {@code maxLength} code points, with every Markdown character escaped, so text from a build
     * renders as the words it is and never as a link, an image, or markup.
     */
    static String plainText(String text, int maxLength) {
        String line = CONTROL.matcher(text).replaceAll(" ").strip();
        if (line.codePointCount(0, line.length()) > maxLength) {
            line = line.substring(0, line.offsetByCodePoints(0, maxLength - 1)) + "…";
        }
        return MARKDOWN.matcher(line).replaceAll("\\\\$0");
    }

    static String detailsUrl(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_DETAILS_URL_LENGTH) {
            return null;
        }
        try {
            URI uri = new URI(url);
            boolean usable = uri.isAbsolute()
                    && URL_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null;
            return usable ? uri.toString() : null;
        } catch (URISyntaxException notAUrl) {
            return null;
        }
    }
}
