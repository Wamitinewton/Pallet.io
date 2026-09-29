package io.pallet.gitintegration.scm;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The source-host port. Everything outside {@code github/} and {@code scm/} works on these provider-neutral types, so a
 * second host needs an adapter, not a change to the push pipeline. Grows one capability per checkpoint that needs it.
 */
public interface ScmProvider {

    String id();

    ScmInstallation installation(long installationId);

    /**
     * One page of the repositories an installation can reach, spending the installation's own budget.
     *
     * @param page 1-based
     * @param etag the first page's last {@code ETag}, or null; ignored past page 1
     */
    RepositoryListing installationRepositories(long installationId, int page, int perPage, String etag);

    /**
     * A branch's head commit, read through the installation with a token narrowed to that repository, so a successful
     * read also proves the installation reaches it.
     *
     * @return empty when the branch doesn't exist
     */
    Optional<String> branchHead(long installationId, long repoId, String branch);

    /**
     * {@link #branchHead(long, long, String)}, conditional on the {@code etag} of an earlier read, spending no budget
     * when the branch hasn't moved since.
     *
     * @param etag null to read unconditionally
     */
    BranchHeadRead branchHead(long installationId, long repoId, String branch, String etag);

    /**
     * How {@code head} relates to {@code base} in one repository, read through the installation.
     *
     * @return empty when the host no longer has one of the two commits
     */
    Optional<CompareStatus> compare(long installationId, long repoId, String base, String head);

    boolean commitExists(long installationId, long repoId, String sha);

    /**
     * The account's highest role on the repository, read through the installation.
     *
     * @return empty when the host answers not found, for the account or the repository alike
     */
    Optional<CollaboratorRole> collaboratorRole(long installationId, long repoId, String login);

    /**
     * The current login of the account with this id, read through the installation.
     *
     * @return empty when the account no longer exists
     */
    Optional<String> accountLogin(long installationId, long accountId);

    /**
     * Removes the app from the installation's account on the host.
     *
     * @return {@code false} when the host has no such installation
     */
    boolean uninstall(long installationId);

    /**
     * The check run this service created for {@code report.commitSha()} under {@code report.name()}, recognised by
     * {@code report.externalId()}.
     *
     * @return empty when there is none
     */
    Optional<Long> findCheckRun(long installationId, long repoId, CheckRunReport report);

    /**
     * Creates a check run, sending it at most once: a failure may still have created it, so a retry looks for it with
     * {@link #findCheckRun} first.
     *
     * @return the new check run's id
     */
    long createCheckRun(long installationId, long repoId, CheckRunReport report);

    void updateCheckRun(long installationId, long repoId, long checkRunId, CheckRunReport report);

    /**
     * What a check run should show. {@code status} is {@code queued}, {@code in_progress} or {@code completed};
     * {@code conclusion} is set exactly when it is {@code completed}. {@code detailsUrl} may be null.
     */
    record CheckRunReport(
            String name,
            String commitSha,
            String externalId,
            String status,
            String conclusion,
            String detailsUrl,
            String title,
            String summary) {}

    /** Repository roles, least to most privileged; a custom role counts as the base role it builds on. */
    enum RepositoryRole {
        NONE,
        READ,
        TRIAGE,
        PUSH,
        MAINTAIN,
        ADMIN;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code accountId} is the account the host answered for, or null when it named none. */
    record CollaboratorRole(Long accountId, RepositoryRole role) {}

    /** Where {@code head} stands relative to {@code base}. */
    enum CompareStatus {
        AHEAD,
        BEHIND,
        IDENTICAL,
        DIVERGED
    }

    /** {@code repositorySelection} is {@code all} or {@code selected}; {@code accountType} is {@code User} or {@code Organization}. */
    record ScmInstallation(
            long id,
            long accountId,
            String accountLogin,
            String accountType,
            String repositorySelection,
            Map<String, String> permissions,
            boolean suspended) {

        public ScmInstallation {
            permissions = Map.copyOf(permissions);
        }

        public boolean allRepositories() {
            return "all".equals(repositorySelection);
        }
    }

    /**
     * A conditional branch read. {@code notModified} means the branch still matches the {@code etag} it was read with,
     * and carries no SHA; otherwise a null {@code sha} means the branch doesn't exist.
     */
    record BranchHeadRead(String sha, String etag, boolean notModified) {

        public boolean missing() {
            return !notModified && sha == null;
        }
    }

    record ScmRepository(long id, String fullName, String defaultBranch, boolean isPrivate, boolean archived) {}

    /** {@code notModified} means the first page still matches its {@code etag}; there are no repositories then. */
    record RepositoryListing(int totalCount, List<ScmRepository> repositories, String etag, boolean notModified) {

        public RepositoryListing {
            repositories = List.copyOf(repositories);
        }
    }
}
