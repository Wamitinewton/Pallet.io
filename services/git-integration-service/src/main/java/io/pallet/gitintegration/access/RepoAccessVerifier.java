package io.pallet.gitintegration.access;

import static io.pallet.gitintegration.observability.MetricsCatalog.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.gitintegration.access.RepoAccessExceptions.BranchNotFoundException;
import io.pallet.gitintegration.access.RepoAccessExceptions.RepositoryArchivedException;
import io.pallet.gitintegration.access.RepoAccessExceptions.RepositoryNotAccessibleException;
import io.pallet.gitintegration.access.RepoAccessExceptions.RepositoryPermissionTooLowException;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubForbiddenException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubNotFoundException;
import io.pallet.gitintegration.scm.ScmProvider;
import io.pallet.gitintegration.session.GitHubAuthorizationService;
import io.pallet.gitintegration.session.GitHubUserSession;
import io.pallet.gitintegration.session.RepositoryAccess;
import org.springframework.stereotype.Component;

/**
 * Proves a repository may be linked (ARCHITECTURE.md §Checking access at link time): the caller's own token shows at
 * least {@code link.min-repo-permission} on it, and a token narrowed to it proves the named installation reaches it.
 * Makes GitHub calls only; callers invoke it before opening a transaction.
 */
@Component
public class RepoAccessVerifier {

    /** Who vouched for the link, with the branch and head it was checked against. */
    public record VerifiedAccess(
            long repoId,
            String fullName,
            String branch,
            String headSha,
            long githubUserId,
            String githubLogin,
            RepoPermission permission) {}

    static final String DENIED_NOT_ACCESSIBLE = "not_accessible";
    static final String DENIED_PERMISSION_TOO_LOW = "permission_too_low";

    private final GitHubAuthorizationService authorizations;
    private final ScmProvider scm;
    private final RepoPermission minPermission;
    private final MeterRegistry meters;

    RepoAccessVerifier(
            GitHubAuthorizationService authorizations,
            ScmProvider scm,
            GitIntegrationProperties properties,
            MeterRegistry meters) {
        this.authorizations = authorizations;
        this.scm = scm;
        this.minPermission = properties.link().minRepoPermission();
        this.meters = meters;
        for (String reason : new String[] {DENIED_NOT_ACCESSIBLE, DENIED_PERMISSION_TOO_LOW}) {
            deniedCounter(reason);
        }
    }

    /**
     * @param accountId the GitHub account the installation belongs to
     * @param branch the branch to check, or null for the repository's default branch
     * @throws RepositoryNotAccessibleException if the caller or the installation can't reach the repository
     * @throws RepositoryArchivedException if the repository is archived
     * @throws RepositoryPermissionTooLowException if the caller's permission is below the floor
     * @throws BranchNotFoundException if the branch doesn't exist
     */
    public VerifiedAccess verify(
            String sub, GitHubUserSession session, long installationId, long accountId, long repoId, String branch) {
        RepositoryAccess repository = authorizations
                .repository(sub, session, repoId)
                .filter(seen -> seen.ownerId() == accountId)
                .orElseThrow(this::notAccessible);
        if (repository.archived()) {
            throw new RepositoryArchivedException();
        }
        RepoPermission permission = RepoPermission.fromWireName(repository.permission())
                .filter(held -> held.atLeast(minPermission))
                .orElseThrow(() -> {
                    deniedCounter(DENIED_PERMISSION_TOO_LOW).increment();
                    return new RepositoryPermissionTooLowException(minPermission);
                });
        String checked = branch == null ? repository.defaultBranch() : branch;
        String headSha = branchHead(installationId, repoId, checked);
        return new VerifiedAccess(
                repoId,
                repository.fullName(),
                checked,
                headSha,
                session.githubUserId(),
                session.githubLogin(),
                permission);
    }

    /**
     * The branch's head through the installation alone, for changes that don't choose a new repository.
     *
     * @throws RepositoryNotAccessibleException if GitHub refuses a token narrowed to the repository
     * @throws BranchNotFoundException if the branch doesn't exist
     */
    public String branchHead(long installationId, long repoId, String branch) {
        try {
            return scm.branchHead(installationId, repoId, branch).orElseThrow(BranchNotFoundException::new);
        } catch (GitHubNotFoundException | GitHubForbiddenException e) {
            throw notAccessible();
        }
    }

    private RepositoryNotAccessibleException notAccessible() {
        deniedCounter(DENIED_NOT_ACCESSIBLE).increment();
        return new RepositoryNotAccessibleException();
    }

    private Counter deniedCounter(String reason) {
        return Counter.builder(LINK_ACCESS_DENIED)
                .description("Repository access checks GitHub answered with a denial, by why")
                .tag(TAG_REASON, reason)
                .register(meters);
    }
}
