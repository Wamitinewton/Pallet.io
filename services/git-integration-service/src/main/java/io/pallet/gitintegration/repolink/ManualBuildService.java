package io.pallet.gitintegration.repolink;

import io.pallet.common.error.BadRequestException;
import io.pallet.common.error.IdempotencyKeyReuseException;
import io.pallet.common.error.TooManyRequestsException;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.outbox.OutboxWriter;
import io.pallet.gitintegration.access.RepoAccessVerifier;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.delivery.PayloadParser;
import io.pallet.gitintegration.delivery.payload.PushPayload;
import io.pallet.gitintegration.installation.Installation;
import io.pallet.gitintegration.installation.InstallationExceptions.InstallationSuspendedException;
import io.pallet.gitintegration.installation.InstallationRepository;
import io.pallet.gitintegration.push.BranchHead;
import io.pallet.gitintegration.push.BranchHeadRepository;
import io.pallet.gitintegration.push.PushEventFactory;
import io.pallet.gitintegration.repolink.ManualBuildRequestRepository.RecentBuilds;
import io.pallet.gitintegration.repolink.RepoLinkExceptions.RepoLinkNotFoundException;
import io.pallet.gitintegration.repolink.dto.ManualBuildRequestDto;
import io.pallet.gitintegration.repolink.dto.ManualBuildResultDto;
import io.pallet.gitintegration.security.AccessExceptions.InstallationNotFoundException;
import io.pallet.gitintegration.security.ResourceScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds a branch's current head on request (ARCHITECTURE.md §API — Repository links). Idempotent by
 * {@code Idempotency-Key}: a replay answers from the stored request without calling GitHub or counting against
 * {@code manual-builds-per-hour}. Never moves a {@link BranchHead}.
 */
@Service
public class ManualBuildService {

    static final int MAX_KEY_LENGTH = 128;

    private final ResourceScope scope;
    private final RepoLinkRepository links;
    private final InstallationRepository installations;
    private final RepoAccessVerifier verifier;
    private final ManualBuildRequestRepository requests;
    private final BranchHeadRepository heads;
    private final PushEventFactory pushes;
    private final OutboxWriter outbox;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int perHour;

    ManualBuildService(
            ResourceScope scope,
            RepoLinkRepository links,
            InstallationRepository installations,
            RepoAccessVerifier verifier,
            ManualBuildRequestRepository requests,
            BranchHeadRepository heads,
            PushEventFactory pushes,
            OutboxWriter outbox,
            PlatformTransactionManager transactionManager,
            Clock clock,
            GitIntegrationProperties properties) {
        this.scope = scope;
        this.links = links;
        this.installations = installations;
        this.verifier = verifier;
        this.requests = requests;
        this.heads = heads;
        this.pushes = pushes;
        this.outbox = outbox;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.perHour = properties.manualBuildsPerHour();
    }

    /**
     * Resolves the branch's head through the installation, then records the request and its {@code MANUAL} push in
     * one transaction under the link's row lock, which makes the hourly limit exact.
     *
     * @throws IdempotencyKeyReuseException if the key was already used for a different request
     * @throws TooManyRequestsException if the app has used its manual builds for the last hour
     */
    public ManualBuildResultDto request(
            String orgId, UUID appId, String sub, String idempotencyKey, ManualBuildRequestDto body) {
        requireKey(idempotencyKey);
        String requested = body == null ? null : branchName(body.branch());
        scope.requireApp(orgId, appId);
        RepoLink link =
                links.findLink(orgId, appId).filter(RepoLink::isActive).orElseThrow(RepoLinkNotFoundException::new);
        requireUsable(link.installationId());
        String requestHash = requestHash(appId, requested);
        Optional<ManualBuildRequest> earlier = requests.findRequest(orgId, appId, idempotencyKey);
        if (earlier.isPresent()) {
            return replay(earlier.get(), requestHash);
        }
        requireWithinLimit(orgId, appId);
        String branch = requested == null ? link.productionBranch() : requested;
        String sha = verifier.branchHead(link.installationId(), link.repoId(), branch);
        return Objects.requireNonNull(transaction.execute(status -> {
            RepoLink locked = links.lockActive(orgId, appId).orElseThrow(RepoLinkNotFoundException::new);
            if (locked.installationId() != link.installationId()
                    || locked.repoId() != link.repoId()
                    || (requested == null && !locked.productionBranch().equals(branch))) {
                throw new OptimisticLockingFailureException("The link changed while the branch head was resolved");
            }
            Optional<ManualBuildRequest> raced = requests.findRequest(orgId, appId, idempotencyKey);
            if (raced.isPresent()) {
                return replay(raced.get(), requestHash);
            }
            requireWithinLimit(orgId, appId);
            String before = heads.findHead(orgId, appId, branch)
                    .map(BranchHead::headSha)
                    .orElse(PushPayload.NULL_SHA);
            GitPushReceived event = pushes.manual(locked, branch, before, sha, idempotencyKey);
            if (requests.insert(orgId, appId, idempotencyKey, requestHash, event.eventId(), branch, sha, sub) == 0) {
                return replay(requests.findRequest(orgId, appId, idempotencyKey).orElseThrow(), requestHash);
            }
            outbox.append(event);
            outbox.append(AuditEvents.buildRequested(orgId, sub, appId, branch, sha, event.eventId(), clock.instant()));
            return new ManualBuildResultDto(event.eventId(), sha, branch);
        }));
    }

    private static ManualBuildResultDto replay(ManualBuildRequest earlier, String requestHash) {
        if (!MessageDigest.isEqual(
                earlier.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new IdempotencyKeyReuseException("This Idempotency-Key was already used for a different build.");
        }
        return new ManualBuildResultDto(earlier.eventId(), earlier.commitSha(), earlier.branch());
    }

    private void requireWithinLimit(String orgId, UUID appId) {
        RecentBuilds recent = requests.lastHour(orgId, appId);
        if (recent.getRequests() >= perHour) {
            long wait = Math.max(1, Objects.requireNonNullElse(recent.getRetryAfterSeconds(), 1L));
            throw new TooManyRequestsException(
                    "This app has reached its limit of " + perHour + " manual builds per hour.",
                    Duration.ofSeconds(wait));
        }
    }

    private void requireUsable(long installationId) {
        Installation installation =
                installations.findById(installationId).orElseThrow(InstallationNotFoundException::new);
        switch (installation.status()) {
            case ACTIVE -> {}
            case SUSPENDED -> throw new InstallationSuspendedException();
            case DELETED -> throw new InstallationNotFoundException();
        }
    }

    private static void requireKey(String key) {
        boolean valid = !key.isEmpty() && key.length() <= MAX_KEY_LENGTH;
        for (int i = 0; valid && i < key.length(); i++) {
            char c = key.charAt(i);
            valid = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
        }
        if (!valid) {
            throw new BadRequestException(
                    "Idempotency-Key must be 1 to " + MAX_KEY_LENGTH + " characters of letters, digits, '_' or '-'.");
        }
    }

    private static String branchName(String branch) {
        if (branch == null) {
            return null;
        }
        PayloadParser.refNameViolation(branch).ifPresent(violation -> {
            throw new BadRequestException("branch " + violation + ".");
        });
        return branch;
    }

    /** Over the request as sent, so {@code {}} and an explicit production branch are different requests. */
    static String requestHash(UUID appId, String requestedBranch) {
        String canonical = appId + "\n" + (requestedBranch == null ? "" : "branch=" + requestedBranch);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM provides SHA-256", e);
        }
    }
}
