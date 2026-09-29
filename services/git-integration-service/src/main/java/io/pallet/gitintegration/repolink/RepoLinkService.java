package io.pallet.gitintegration.repolink;

import io.pallet.common.error.BadRequestException;
import io.pallet.common.outbox.OutboxWriter;
import io.pallet.gitintegration.access.RepoAccessVerifier;
import io.pallet.gitintegration.access.RepoAccessVerifier.VerifiedAccess;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.delivery.PayloadParser;
import io.pallet.gitintegration.installation.Installation;
import io.pallet.gitintegration.installation.InstallationExceptions.InstallationSuspendedException;
import io.pallet.gitintegration.installation.InstallationLink;
import io.pallet.gitintegration.installation.InstallationLinkRepository;
import io.pallet.gitintegration.installation.InstallationRepository;
import io.pallet.gitintegration.installation.InstallationRepositoryEntryRepository;
import io.pallet.gitintegration.projection.AppProjection;
import io.pallet.gitintegration.projection.AppProjectionRepository;
import io.pallet.gitintegration.push.BranchHeadRepository;
import io.pallet.gitintegration.push.PushEventFactory;
import io.pallet.gitintegration.repolink.RepoLink.DisconnectReason;
import io.pallet.gitintegration.repolink.RepoLinkExceptions.RepoLinkExistsException;
import io.pallet.gitintegration.repolink.RepoLinkExceptions.RepoLinkNotFoundException;
import io.pallet.gitintegration.repolink.dto.PatchRepoLinkRequest;
import io.pallet.gitintegration.repolink.dto.PutRepoLinkRequest;
import io.pallet.gitintegration.repolink.dto.RepoLinkDto;
import io.pallet.gitintegration.security.AccessExceptions.AppNotFoundException;
import io.pallet.gitintegration.security.AccessExceptions.InstallationNotFoundException;
import io.pallet.gitintegration.security.ResourceScope;
import io.pallet.gitintegration.session.GitHubAuthorizationService;
import io.pallet.gitintegration.session.GitHubUserSession;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Linking an app to a repository (ARCHITECTURE.md §Linking a repository to an app). Every GitHub call happens before
 * the transaction opens; the transaction re-reads, under lock, everything those calls were made against.
 */
@Service
public class RepoLinkService {

    private final ResourceScope scope;
    private final GitHubAuthorizationService authorizations;
    private final RepoAccessVerifier verifier;
    private final InstallationRepository installations;
    private final InstallationLinkRepository installationLinks;
    private final InstallationRepositoryEntryRepository repositories;
    private final AppProjectionRepository apps;
    private final RepoLinkRepository links;
    private final BranchHeadRepository heads;
    private final PushEventFactory pushes;
    private final OutboxWriter outbox;
    private final TransactionTemplate transaction;
    private final Clock clock;

    RepoLinkService(
            ResourceScope scope,
            GitHubAuthorizationService authorizations,
            RepoAccessVerifier verifier,
            InstallationRepository installations,
            InstallationLinkRepository installationLinks,
            InstallationRepositoryEntryRepository repositories,
            AppProjectionRepository apps,
            RepoLinkRepository links,
            BranchHeadRepository heads,
            PushEventFactory pushes,
            OutboxWriter outbox,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.scope = scope;
        this.authorizations = authorizations;
        this.verifier = verifier;
        this.installations = installations;
        this.installationLinks = installationLinks;
        this.repositories = repositories;
        this.apps = apps;
        this.links = links;
        this.heads = heads;
        this.pushes = pushes;
        this.outbox = outbox;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Links the app after the caller's own GitHub token shows enough permission on the repository and a
     * repository-scoped token shows the installation reaches it. Emits a {@code LINKED} push only for {@code deployNow}.
     *
     * @throws RepoLinkExistsException if the app already has an active link, including one created concurrently
     */
    public RepoLinkDto link(String orgId, UUID appId, String sub, PutRepoLinkRequest request) {
        scope.requireApp(orgId, appId);
        String branch = branchName(request.productionBranch());
        String rootDirectory = RootDirectory.normalize(request.rootDirectory());
        long installationId = request.installationId();
        scope.requireInstallation(orgId, installationId);
        Installation installation = requireUsable(installationId);
        if (links.existsByOrgIdAndAppIdAndStatus(orgId, appId, RepoLink.Status.ACTIVE)) {
            throw new RepoLinkExistsException();
        }
        GitHubUserSession session = authorizations.requireSession(sub);
        VerifiedAccess access =
                verifier.verify(sub, session, installationId, installation.accountId(), request.repoId(), branch);
        return inTransaction(() -> {
            requireLinkedInstallation(orgId, installationId);
            requireActiveApp(orgId, appId);
            int linked = links.upsertActive(
                    orgId,
                    appId,
                    installationId,
                    access.repoId(),
                    access.fullName(),
                    access.branch(),
                    rootDirectory,
                    request.autoDeployOrDefault(),
                    sub,
                    access.githubUserId(),
                    access.githubLogin(),
                    access.permission().wireName());
            if (linked == 0) {
                throw new RepoLinkExistsException();
            }
            heads.deleteAll(orgId, appId);
            heads.insert(orgId, appId, access.branch(), access.headSha());
            RepoLink link = requireLink(orgId, appId);
            if (request.deployNowOrDefault()) {
                outbox.append(pushes.linked(link, access.headSha()));
            }
            outbox.append(AuditEvents.repoLinkCreated(
                    orgId,
                    sub,
                    appId,
                    installationId,
                    access.repoId(),
                    access.fullName(),
                    access.branch(),
                    access.githubLogin(),
                    access.permission().wireName(),
                    clock.instant()));
            return view(link);
        });
    }

    public RepoLinkDto get(String orgId, UUID appId) {
        scope.requireApp(orgId, appId);
        return view(requireLink(orgId, appId));
    }

    /**
     * Changes the branch, directory, or auto-deploy of the active link. A new branch must exist on GitHub, and the old
     * branch's accepted head is dropped so the next push to the new one starts its chain.
     *
     * @throws OptimisticLockingFailureException if {@code version} is not the link's current one
     */
    public RepoLinkDto update(String orgId, UUID appId, String sub, PatchRepoLinkRequest request) {
        scope.requireApp(orgId, appId);
        RepoLink current = requireActive(orgId, appId);
        requireVersion(current.version(), request.version());
        String branch = request.productionBranch() == null
                ? current.productionBranch()
                : branchName(request.productionBranch());
        String rootDirectory = request.rootDirectory() == null
                ? current.rootDirectory()
                : RootDirectory.normalize(request.rootDirectory());
        boolean autoDeploy = request.autoDeploy() == null ? current.autoDeploy() : request.autoDeploy();
        boolean branchChanged = !branch.equals(current.productionBranch());
        List<String> changed = new ArrayList<>();
        if (branchChanged) {
            verifier.branchHead(current.installationId(), current.repoId(), branch);
            changed.add("productionBranch");
        }
        if (!Objects.equals(rootDirectory, current.rootDirectory())) {
            changed.add("rootDirectory");
        }
        if (autoDeploy != current.autoDeploy()) {
            changed.add("autoDeploy");
        }
        return inTransaction(() -> {
            RepoLink locked = links.lockActive(orgId, appId).orElseThrow(RepoLinkNotFoundException::new);
            requireVersion(locked.version(), request.version());
            if (changed.isEmpty()) {
                return view(locked);
            }
            links.updateSettings(orgId, appId, branch, rootDirectory, autoDeploy);
            if (branchChanged) {
                heads.deleteBranch(orgId, appId, current.productionBranch());
            }
            outbox.append(AuditEvents.repoLinkUpdated(orgId, sub, appId, changed, clock.instant()));
            return view(requireLink(orgId, appId));
        });
    }

    /**
     * Makes the caller the link's verifier after the same access check as linking, for handing over before the current
     * one loses access on GitHub. A caller who fails the check changes nothing.
     */
    public RepoLinkDto takeOverVerification(String orgId, UUID appId, String sub) {
        scope.requireApp(orgId, appId);
        RepoLink current = requireActive(orgId, appId);
        Installation installation = requireUsable(current.installationId());
        GitHubUserSession session = authorizations.requireSession(sub);
        VerifiedAccess access = verifier.verify(
                sub,
                session,
                current.installationId(),
                installation.accountId(),
                current.repoId(),
                current.productionBranch());
        return inTransaction(() -> {
            RepoLink locked = links.lockActive(orgId, appId).orElseThrow(RepoLinkNotFoundException::new);
            if (locked.installationId() != current.installationId() || locked.repoId() != current.repoId()) {
                throw new OptimisticLockingFailureException("The link moved to another repository during the check");
            }
            links.updateVerifier(
                    orgId,
                    appId,
                    sub,
                    access.githubUserId(),
                    access.githubLogin(),
                    access.permission().wireName(),
                    access.fullName());
            outbox.append(AuditEvents.repoLinkVerifierChanged(
                    orgId,
                    sub,
                    appId,
                    locked.verifiedGithubLogin(),
                    access.githubLogin(),
                    access.permission().wireName(),
                    clock.instant()));
            return view(requireLink(orgId, appId));
        });
    }

    /**
     * Disconnects the link; pushes for the app stop at commit. Calls nothing on GitHub.
     *
     * @throws RepoLinkNotFoundException if the app has no active link
     */
    public void disconnect(String orgId, UUID appId, String sub) {
        scope.requireApp(orgId, appId);
        DisconnectReason reason = DisconnectReason.UNLINKED_BY_USER;
        transaction.executeWithoutResult(status -> {
            if (links.disconnect(orgId, appId, reason.name()) == 0) {
                throw new RepoLinkNotFoundException();
            }
            heads.deleteAll(orgId, appId);
            outbox.append(AuditEvents.repoLinkDisconnected(orgId, sub, appId, reason.name(), clock.instant()));
        });
    }

    private Installation requireUsable(long installationId) {
        Installation installation =
                installations.findById(installationId).orElseThrow(InstallationNotFoundException::new);
        return switch (installation.status()) {
            case ACTIVE -> installation;
            case SUSPENDED -> throw new InstallationSuspendedException();
            case DELETED -> throw new InstallationNotFoundException();
        };
    }

    /** An unlink that committed while GitHub was being asked fails the link here instead of leaving it orphaned. */
    private void requireLinkedInstallation(String orgId, long installationId) {
        String status =
                installationLinks.shareStatus(orgId, installationId).orElseThrow(InstallationNotFoundException::new);
        if (!InstallationLink.Status.ACTIVE.name().equals(status)) {
            throw new InstallationNotFoundException();
        }
    }

    private void requireActiveApp(String orgId, UUID appId) {
        String status = apps.shareStatus(orgId, appId).orElseThrow(AppNotFoundException::new);
        if (!AppProjection.Status.ACTIVE.name().equals(status)) {
            throw new AppNotFoundException();
        }
    }

    private RepoLink requireLink(String orgId, UUID appId) {
        return links.findLink(orgId, appId).orElseThrow(RepoLinkNotFoundException::new);
    }

    private RepoLink requireActive(String orgId, UUID appId) {
        return links.findLink(orgId, appId).filter(RepoLink::isActive).orElseThrow(RepoLinkNotFoundException::new);
    }

    private static void requireVersion(long current, long expected) {
        if (current != expected) {
            throw new OptimisticLockingFailureException("repo link version " + expected + " is stale");
        }
    }

    private static String branchName(String branch) {
        if (branch == null) {
            return null;
        }
        PayloadParser.refNameViolation(branch).ifPresent(violation -> {
            throw new BadRequestException("productionBranch " + violation + ".");
        });
        return branch;
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transaction.execute(status -> work.get()));
    }

    private RepoLinkDto view(RepoLink link) {
        RepoLinkDto.Head head = heads.findHead(link.orgId(), link.appId(), link.productionBranch())
                .map(found -> new RepoLinkDto.Head(found.branch(), found.headSha(), found.advancedAt()))
                .orElse(null);
        RepoLinkDto.Verification verification = link.verifiedGithubLogin() == null
                ? null
                : new RepoLinkDto.Verification(
                        link.verifiedByUserId(),
                        link.verifiedGithubLogin(),
                        link.verifiedPermission(),
                        link.accessVerifiedAt(),
                        link.accessCheckedAt());
        List<String> warnings = link.isActive() && repositories.isArchived(link.installationId(), link.repoId())
                ? List.of(RepoLinkDto.REPOSITORY_ARCHIVED)
                : List.of();
        return new RepoLinkDto(
                link.appId(),
                link.installationId(),
                link.repoId(),
                link.repoFullName(),
                link.productionBranch(),
                link.rootDirectory(),
                link.autoDeploy(),
                link.status().name(),
                link.disconnectReason(),
                link.disconnectedAt(),
                warnings,
                head,
                verification,
                link.version(),
                link.createdAt(),
                link.updatedAt());
    }
}
