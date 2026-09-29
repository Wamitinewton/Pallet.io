package io.pallet.gitintegration.scm;

import io.pallet.gitintegration.github.CheckRunWrite;
import io.pallet.gitintegration.github.GitHubClient;
import io.pallet.gitintegration.github.GitHubResponse;
import io.pallet.gitintegration.github.dto.CollaboratorPermission;
import io.pallet.gitintegration.github.dto.GitHubUser;
import io.pallet.gitintegration.github.dto.Installation;
import io.pallet.gitintegration.github.dto.InstallationRepositories;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class GitHubScmProvider implements ScmProvider {

    static final String ID = "github";

    /** GitHub's {@code role_name} for its built-in roles; {@code write} is what the rest of the API calls push. */
    private static final Map<String, RepositoryRole> ROLE_NAMES = Map.of(
            "admin", RepositoryRole.ADMIN,
            "maintain", RepositoryRole.MAINTAIN,
            "write", RepositoryRole.PUSH,
            "triage", RepositoryRole.TRIAGE,
            "read", RepositoryRole.READ);

    private static final Map<String, RepositoryRole> BASE_PERMISSIONS = Map.of(
            "admin", RepositoryRole.ADMIN,
            "write", RepositoryRole.PUSH,
            "read", RepositoryRole.READ);

    private final GitHubClient github;

    GitHubScmProvider(GitHubClient github) {
        this.github = github;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ScmInstallation installation(long installationId) {
        Installation installation = github.getInstallation(installationId);
        return new ScmInstallation(
                installation.id(),
                installation.account().id(),
                installation.account().login(),
                installation.account().type(),
                installation.repositorySelection(),
                installation.permissions(),
                installation.suspended());
    }

    @Override
    public RepositoryListing installationRepositories(long installationId, int page, int perPage, String etag) {
        GitHubResponse<InstallationRepositories> response =
                github.installationRepositories(installationId, page, perPage, etag);
        if (response.notModified()) {
            return new RepositoryListing(0, List.of(), response.etag(), true);
        }
        InstallationRepositories body = response.body();
        return new RepositoryListing(
                body.totalCount(),
                body.repositories().stream()
                        .map(repository -> new ScmRepository(
                                repository.id(),
                                repository.fullName(),
                                repository.defaultBranch(),
                                repository.isPrivate(),
                                repository.archived()))
                        .toList(),
                response.etag(),
                false);
    }

    @Override
    public Optional<String> branchHead(long installationId, long repoId, String branch) {
        return github.branchHead(installationId, repoId, branch);
    }

    @Override
    public BranchHeadRead branchHead(long installationId, long repoId, String branch, String etag) {
        return github.branchHead(installationId, repoId, branch, etag)
                .map(read -> new BranchHeadRead(read.body(), read.etag(), read.notModified()))
                .orElseGet(() -> new BranchHeadRead(null, null, false));
    }

    @Override
    public Optional<CompareStatus> compare(long installationId, long repoId, String base, String head) {
        return github.compare(installationId, repoId, base, head)
                .map(status -> CompareStatus.valueOf(status.toUpperCase(Locale.ROOT)));
    }

    @Override
    public boolean commitExists(long installationId, long repoId, String sha) {
        return github.commitExists(installationId, repoId, sha);
    }

    @Override
    public Optional<CollaboratorRole> collaboratorRole(long installationId, long repoId, String login) {
        return github.collaboratorPermission(installationId, repoId, login)
                .map(answer -> new CollaboratorRole(
                        answer.user() == null ? null : answer.user().id(), role(answer)));
    }

    @Override
    public Optional<String> accountLogin(long installationId, long accountId) {
        return github.user(installationId, accountId).map(GitHubUser::login);
    }

    @Override
    public boolean uninstall(long installationId) {
        return github.deleteInstallation(installationId);
    }

    @Override
    public Optional<Long> findCheckRun(long installationId, long repoId, CheckRunReport report) {
        return github.findCheckRun(installationId, repoId, report.commitSha(), report.name(), report.externalId());
    }

    @Override
    public long createCheckRun(long installationId, long repoId, CheckRunReport report) {
        return github.createCheckRun(installationId, repoId, write(report));
    }

    @Override
    public void updateCheckRun(long installationId, long repoId, long checkRunId, CheckRunReport report) {
        github.updateCheckRun(installationId, repoId, checkRunId, write(report));
    }

    private static CheckRunWrite write(CheckRunReport report) {
        return new CheckRunWrite(
                report.name(),
                report.commitSha(),
                report.externalId(),
                report.status(),
                report.conclusion(),
                report.detailsUrl(),
                report.title(),
                report.summary());
    }

    /** A custom role's name means nothing here, so it counts as the base permission GitHub reports under it. */
    private static RepositoryRole role(CollaboratorPermission answer) {
        RepositoryRole named = ROLE_NAMES.get(answer.roleName());
        return named != null ? named : BASE_PERMISSIONS.getOrDefault(answer.permission(), RepositoryRole.NONE);
    }
}
