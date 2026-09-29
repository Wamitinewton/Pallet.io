package io.pallet.gitintegration.github;

import io.pallet.common.error.AppException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubNotFoundException;
import io.pallet.gitintegration.github.GitHubExceptions.UserAuthorizationRejectedException;
import io.pallet.gitintegration.github.dto.Branch;
import io.pallet.gitintegration.github.dto.CheckRun;
import io.pallet.gitintegration.github.dto.CollaboratorPermission;
import io.pallet.gitintegration.github.dto.Commit;
import io.pallet.gitintegration.github.dto.Comparison;
import io.pallet.gitintegration.github.dto.GitHubUser;
import io.pallet.gitintegration.github.dto.HookDelivery;
import io.pallet.gitintegration.github.dto.Installation;
import io.pallet.gitintegration.github.dto.InstallationRepositories;
import io.pallet.gitintegration.github.dto.Repository;
import io.pallet.gitintegration.github.dto.UserInstallations;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The only way the service talks to GitHub: one method per endpoint, each naming its endpoint, credential, and error
 * mapping. The credential fixes the policy ({@code github-api} or {@code github-user}); no caller outside
 * {@code github/} and {@code scm/} sees a status code, a header, or a token.
 */
public class GitHubClient {

    /** GitHub's own {@code per_page} ceiling. */
    public static final int MAX_PER_PAGE = 100;

    private static final Pattern OAUTH_ERROR = Pattern.compile("[a-z_]{1,64}");
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9-]{1,39}");
    private static final Pattern FULL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,39}/[A-Za-z0-9._-]{1,100}");
    private static final Pattern PERMISSION_WORD = Pattern.compile("[a-z_]{1,64}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern DELIVERY_CURSOR = Pattern.compile("[A-Za-z0-9_=.-]{1,256}");
    private static final String NEXT_RELATION = "rel=\"next\"";
    private static final Set<String> ACCOUNT_TYPES = Set.of("User", "Organization");
    private static final Set<String> REPOSITORY_SELECTIONS = Set.of("all", "selected");
    private static final Set<String> COMPARE_STATUSES = Set.of("ahead", "behind", "identical", "diverged");
    private static final int MAX_BRANCH_LENGTH = 255;
    private static final int MAX_ROLE_NAME_LENGTH = 100;
    private static final TokenScope METADATA_READ =
            new TokenScope(Set.of(), Map.of("metadata", TokenScope.Access.READ));

    private final GitHubHttp http;
    private final AppJwtSigner appJwt;
    private final InstallationTokenCache tokens;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    GitHubClient(
            GitHubHttp http,
            AppJwtSigner appJwt,
            InstallationTokenCache tokens,
            String clientId,
            String clientSecret,
            Clock clock) {
        this.http = http;
        this.appJwt = appJwt;
        this.tokens = tokens;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    /** @throws IllegalStateException if GitHub answers with another installation or facts the schema can't hold */
    public Installation getInstallation(long installationId) {
        Installation installation = asApp(GitHubRequest.get(
                        "installation.get", Installation.class, "/app/installations/{id}", installationId))
                .body();
        if (installation == null
                || installation.id() != installationId
                || installation.account() == null
                || installation.account().id() <= 0
                || installation.account().login() == null
                || !LOGIN.matcher(installation.account().login()).matches()
                || !ACCOUNT_TYPES.contains(installation.account().type())
                || !REPOSITORY_SELECTIONS.contains(installation.repositorySelection())
                || !installation.permissions().entrySet().stream()
                        .allMatch(permission ->
                                PERMISSION_WORD.matcher(permission.getKey()).matches()
                                        && permission.getValue() != null
                                        && PERMISSION_WORD
                                                .matcher(permission.getValue())
                                                .matches())) {
            throw new IllegalStateException("GitHub answered GET /app/installations/{id} with an invalid installation");
        }
        return installation;
    }

    /**
     * One page of the repositories the installation can reach, with a metadata-only token. A {@code 304} for
     * {@code etag} comes back as {@link GitHubResponse#notModified()} with no body.
     *
     * @param page 1-based, as GitHub counts
     */
    public GitHubResponse<InstallationRepositories> installationRepositories(
            long installationId, int page, int perPage, String etag) {
        requirePage(page, perPage);
        GitHubRequest<InstallationRepositories> request = GitHubRequest.get(
                "installation.repositories.list",
                InstallationRepositories.class,
                "/installation/repositories?per_page={perPage}&page={page}",
                perPage,
                page);
        GitHubResponse<InstallationRepositories> response = asInstallation(
                installationId, METADATA_READ, etag == null || page != 1 ? request : request.ifNoneMatch(etag));
        if (!response.notModified()) {
            requireRepositories(response.body(), "GET /installation/repositories");
        }
        return response;
    }

    /**
     * One page of the repositories both the user and the installation can reach, each with the user's own permissions.
     *
     * @param page 1-based, as GitHub counts
     */
    public InstallationRepositories userInstallationRepositories(
            GitHubUserToken token, long installationId, int page, int perPage) {
        requirePage(page, perPage);
        InstallationRepositories repositories = asUser(
                        token,
                        GitHubRequest.get(
                                "user.installation.repositories.list",
                                InstallationRepositories.class,
                                "/user/installations/{id}/repositories?per_page={perPage}&page={page}",
                                installationId,
                                perPage,
                                page))
                .body();
        return requireRepositories(repositories, "GET /user/installations/{id}/repositories");
    }

    /**
     * A repository as the user's own token sees it, with their permissions on it. GitHub answers {@code 404} for one
     * the user can't reach, and for one no installation of the app can.
     *
     * @throws GitHubExceptions.GitHubNotFoundException for a repository hidden from the user or the app
     */
    public Repository repository(GitHubUserToken token, long repoId) {
        Repository repository = asUser(
                        token, GitHubRequest.get("repository.get", Repository.class, "/repositories/{id}", repoId))
                .body();
        if (repository == null
                || repository.id() != repoId
                || repository.fullName() == null
                || !FULL_NAME.matcher(repository.fullName()).matches()
                || repository.defaultBranch() == null
                || repository.defaultBranch().isBlank()
                || repository.defaultBranch().length() > MAX_BRANCH_LENGTH
                || repository.owner() == null
                || repository.owner().id() <= 0) {
            throw new IllegalStateException("GitHub answered GET /repositories/{id} with an invalid repository");
        }
        return repository;
    }

    /**
     * The head commit of a branch, read with a token narrowed to this one repository and {@code contents: read}. GitHub
     * refuses that mint when the installation doesn't reach the repository, so a successful read also proves reach.
     *
     * @return empty when the branch doesn't exist
     * @throws GitHubExceptions.GitHubNotFoundException if GitHub refused the repository-scoped mint
     */
    public Optional<String> branchHead(long installationId, long repoId, String branch) {
        return branchHead(installationId, repoId, branch, null).map(GitHubResponse::body);
    }

    /**
     * {@link #branchHead(long, long, String)}, conditional on {@code etag}: a branch that still matches it comes back
     * as {@link GitHubResponse#notModified()}, which costs no budget. The body of any other answer is the head SHA.
     *
     * @param etag the {@code ETag} of an earlier read of this branch, or null
     * @return empty when the branch doesn't exist
     */
    public Optional<GitHubResponse<String>> branchHead(long installationId, long repoId, String branch, String etag) {
        GitHubRequest<Branch> request = GitHubRequest.get(
                        "repository.branch.get", Branch.class, "/repositories/{id}/branches/{branch}", repoId, branch)
                .onNotFound(BranchMissingException::new);
        GitHubResponse<Branch> response;
        try {
            response = asInstallation(
                    installationId, contentsRead(repoId), etag == null ? request : request.ifNoneMatch(etag));
        } catch (BranchMissingException e) {
            return Optional.empty();
        }
        if (response.notModified()) {
            return Optional.of(GitHubResponse.unchanged(response.etag()));
        }
        Branch body = response.body();
        if (body == null
                || body.commit() == null
                || body.commit().sha() == null
                || !SHA.matcher(body.commit().sha()).matches()) {
            throw new IllegalStateException("GitHub answered GET /repositories/{id}/branches/{branch} without a SHA");
        }
        return Optional.of(GitHubResponse.of(body.commit().sha(), response.etag(), null));
    }

    /**
     * How {@code head} relates to {@code base}: {@code ahead}, {@code behind}, {@code identical}, or {@code diverged}.
     * Read with a token narrowed to this repository and {@code contents: read}, asking for one commit per page so the
     * answer stays small.
     *
     * @return empty when GitHub no longer has one of the two commits
     */
    public Optional<String> compare(long installationId, long repoId, String base, String head) {
        requireSha(base);
        requireSha(head);
        GitHubResponse<Comparison> response;
        try {
            response = asInstallation(
                    installationId,
                    contentsRead(repoId),
                    GitHubRequest.get(
                                    "repository.compare",
                                    Comparison.class,
                                    "/repositories/{id}/compare/{base}...{head}?per_page=1",
                                    repoId,
                                    base,
                                    head)
                            .onNotFound(CommitMissingException::new));
        } catch (CommitMissingException e) {
            return Optional.empty();
        }
        Comparison body = response.body();
        if (body == null || body.status() == null || !COMPARE_STATUSES.contains(body.status())) {
            throw new IllegalStateException("GitHub answered GET /repositories/{id}/compare without a known status");
        }
        return Optional.of(body.status());
    }

    /** @return whether GitHub still has the commit in this repository */
    public boolean commitExists(long installationId, long repoId, String sha) {
        requireSha(sha);
        GitHubResponse<Commit> response;
        try {
            response = asInstallation(
                    installationId,
                    contentsRead(repoId),
                    GitHubRequest.get(
                                    "repository.commit.get",
                                    Commit.class,
                                    "/repositories/{id}/commits/{sha}",
                                    repoId,
                                    sha)
                            .onNotFound(CommitMissingException::new));
        } catch (CommitMissingException e) {
            return false;
        }
        Commit body = response.body();
        if (body == null || body.sha() == null || !SHA.matcher(body.sha()).matches()) {
            throw new IllegalStateException("GitHub answered GET /repositories/{id}/commits/{sha} without a SHA");
        }
        return true;
    }

    /**
     * The user's highest role on the repository from every source of grants, read with a metadata-only token for the
     * whole installation, so one token serves every repository checked there.
     *
     * @return empty when GitHub answers not found, which it does alike for an unknown user and a repository the
     *     installation can't reach
     */
    public Optional<CollaboratorPermission> collaboratorPermission(long installationId, long repoId, String login) {
        if (login == null || !LOGIN.matcher(login).matches()) {
            throw new IllegalArgumentException("Not a GitHub login");
        }
        GitHubResponse<CollaboratorPermission> response;
        try {
            response = asInstallation(
                    installationId,
                    METADATA_READ,
                    GitHubRequest.get(
                                    "repository.collaborator.permission.get",
                                    CollaboratorPermission.class,
                                    "/repositories/{id}/collaborators/{login}/permission",
                                    repoId,
                                    login)
                            .onNotFound(AccountMissingException::new));
        } catch (AccountMissingException e) {
            return Optional.empty();
        }
        CollaboratorPermission body = response.body();
        if (body == null
                || body.permission() == null
                || !PERMISSION_WORD.matcher(body.permission()).matches()
                || body.roleName() == null
                || body.roleName().isBlank()
                || body.roleName().length() > MAX_ROLE_NAME_LENGTH
                || (body.user() != null && body.user().id() <= 0)) {
            throw new IllegalStateException(
                    "GitHub answered GET /repositories/{id}/collaborators/{login}/permission without a valid role");
        }
        return Optional.of(body);
    }

    /**
     * A GitHub account by its id, which never changes, read through the installation.
     *
     * @return empty when GitHub has no account with that id any more
     */
    public Optional<GitHubUser> user(long installationId, long githubUserId) {
        if (githubUserId <= 0) {
            throw new IllegalArgumentException("A GitHub user id is positive");
        }
        GitHubResponse<GitHubUser> response;
        try {
            response = asInstallation(
                    installationId,
                    METADATA_READ,
                    GitHubRequest.get("user.by_id.get", GitHubUser.class, "/user/{id}", githubUserId)
                            .onNotFound(AccountMissingException::new));
        } catch (AccountMissingException e) {
            return Optional.empty();
        }
        GitHubUser user = response.body();
        if (user == null
                || user.id() != githubUserId
                || user.login() == null
                || !LOGIN.matcher(user.login()).matches()) {
            throw new IllegalStateException("GitHub answered GET /user/{id} with another or an invalid account");
        }
        return Optional.of(user);
    }

    /**
     * One page of the app's webhook delivery log, newest first, with the app JWT.
     *
     * @param cursor the previous page's {@link HookDeliveryPage#nextCursor()}, or null for the newest page
     */
    public HookDeliveryPage hookDeliveries(String cursor, int perPage) {
        requirePage(1, perPage);
        if (cursor != null && !DELIVERY_CURSOR.matcher(cursor).matches()) {
            throw new IllegalArgumentException("Not a delivery log cursor");
        }
        GitHubRequest<HookDelivery[]> request = cursor == null
                ? GitHubRequest.get(
                        "app.hook.deliveries.list",
                        HookDelivery[].class,
                        "/app/hook/deliveries?per_page={perPage}",
                        perPage)
                : GitHubRequest.get(
                        "app.hook.deliveries.list",
                        HookDelivery[].class,
                        "/app/hook/deliveries?per_page={perPage}&cursor={cursor}",
                        perPage,
                        cursor);
        GitHubResponse<HookDelivery[]> response = asApp(request);
        HookDelivery[] deliveries = response.body();
        if (deliveries == null) {
            throw new IllegalStateException("GitHub answered GET /app/hook/deliveries without a list");
        }
        for (HookDelivery delivery : deliveries) {
            if (delivery == null
                    || delivery.id() <= 0
                    || delivery.guid() == null
                    || delivery.deliveredAt() == null
                    || delivery.status() == null) {
                throw new IllegalStateException("GitHub answered GET /app/hook/deliveries with an invalid delivery");
            }
        }
        return new HookDeliveryPage(Arrays.asList(deliveries), nextCursor(response.link()));
    }

    /** Asks GitHub to send the delivery again; it arrives through the webhook with its original GUID. */
    public void redeliver(long hookDeliveryId) {
        if (hookDeliveryId <= 0) {
            throw new IllegalArgumentException("A hook delivery id is positive");
        }
        asApp(GitHubRequest.post(
                "app.hook.delivery.redeliver", Void.class, "/app/hook/deliveries/{id}/attempts", hookDeliveryId));
    }

    /**
     * Uninstalls the app from the installation's account, with the app JWT.
     *
     * @return {@code false} when GitHub has no such installation, so there was nothing to uninstall
     */
    public boolean deleteInstallation(long installationId) {
        if (installationId <= 0) {
            throw new IllegalArgumentException("An installation id is positive");
        }
        try {
            asApp(GitHubRequest.delete("installation.delete", Void.class, "/app/installations/{id}", installationId));
            return true;
        } catch (GitHubNotFoundException e) {
            return false;
        }
    }

    public InstallationToken mintToken(long installationId, TokenScope scope) {
        GitHubRequest<InstallationToken> request = GitHubRequest.post(
                "installation.token.create",
                InstallationToken.class,
                "/app/installations/{id}/access_tokens",
                installationId);
        InstallationToken token = asApp(scope.isNarrowed() ? request.withBody(scope.requestBody()) : request)
                .body();
        if (token == null || token.value() == null || token.value().isBlank() || token.expiresAt() == null) {
            throw new IllegalStateException("GitHub returned an installation token without a value or expiry");
        }
        return token;
    }

    /**
     * The check run this app created for the commit under {@code name}, recognised by its {@code external_id}, so a
     * create whose answer was lost is found instead of repeated. Read with a token narrowed to this repository.
     *
     * @return the lowest matching id, or empty when there is none
     */
    public Optional<Long> findCheckRun(
            long installationId, long repoId, String headSha, String name, String externalId) {
        requireSha(headSha);
        CheckRun.Page page = asInstallation(
                        installationId,
                        checksWrite(repoId),
                        GitHubRequest.get(
                                "repository.check_runs.list",
                                CheckRun.Page.class,
                                "/repositories/{id}/commits/{sha}/check-runs?check_name={name}&filter=all&per_page={perPage}",
                                repoId,
                                headSha,
                                name,
                                MAX_PER_PAGE))
                .body();
        if (page == null || page.checkRuns() == null) {
            throw new IllegalStateException(
                    "GitHub answered GET /repositories/{id}/commits/{sha}/check-runs without a list");
        }
        return page.checkRuns().stream()
                .filter(run ->
                        run != null && run.id() > 0 && name.equals(run.name()) && externalId.equals(run.externalId()))
                .map(CheckRun::id)
                .min(Comparator.naturalOrder());
    }

    /**
     * Creates a check run. Sent at most once per call: when GitHub's answer is lost the call fails with
     * {@link GitHubExceptions.WriteOutcomeUnknownException}, and the caller looks for the run before creating again.
     *
     * @return the new check run's id
     */
    public long createCheckRun(long installationId, long repoId, CheckRunWrite write) {
        requireSha(write.headSha());
        Map<String, Object> body = checkRunBody(write);
        body.put("head_sha", write.headSha());
        CheckRun created = asInstallation(
                        installationId,
                        checksWrite(repoId),
                        GitHubRequest.post(
                                        "repository.check_run.create",
                                        CheckRun.class,
                                        "/repositories/{id}/check-runs",
                                        repoId)
                                .withBody(body)
                                .sentAtMostOnce())
                .body();
        return requireCheckRun(created, "POST /repositories/{id}/check-runs");
    }

    /** Moves an existing check run to {@code write}'s status, conclusion and output. Repeating it changes nothing. */
    public void updateCheckRun(long installationId, long repoId, long checkRunId, CheckRunWrite write) {
        if (checkRunId <= 0) {
            throw new IllegalArgumentException("A check run id is positive");
        }
        CheckRun updated = asInstallation(
                        installationId,
                        checksWrite(repoId),
                        GitHubRequest.patch(
                                        "repository.check_run.update",
                                        CheckRun.class,
                                        "/repositories/{id}/check-runs/{checkRunId}",
                                        repoId,
                                        checkRunId)
                                .withBody(checkRunBody(write)))
                .body();
        if (requireCheckRun(updated, "PATCH /repositories/{id}/check-runs/{id}") != checkRunId) {
            throw new IllegalStateException(
                    "GitHub answered PATCH /repositories/{id}/check-runs/{id} with another run");
        }
    }

    private static Map<String, Object> checkRunBody(CheckRunWrite write) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", write.name());
        body.put("external_id", write.externalId());
        body.put("status", write.status());
        if (write.conclusion() != null) {
            body.put("conclusion", write.conclusion());
        }
        if (write.detailsUrl() != null) {
            body.put("details_url", write.detailsUrl());
        }
        body.put("output", Map.of("title", write.title(), "summary", write.summary()));
        return body;
    }

    private static long requireCheckRun(CheckRun run, String endpoint) {
        if (run == null || run.id() <= 0) {
            throw new IllegalStateException("GitHub answered " + endpoint + " without a check run id");
        }
        return run.id();
    }

    /**
     * Exchanges a user authorization code for a user token. There is no {@code redirect_uri}: GitHub uses the app's
     * configured callback.
     *
     * @throws UserAuthorizationRejectedException when GitHub answers with an {@code error} field
     */
    public ExchangedUserToken exchangeCode(String code) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        body.put("code", code);
        Instant requestedAt = clock.instant();
        OAuthTokenResponse response = http.execute(
                        GitHubRequest.post(
                                        "oauth.token.exchange", OAuthTokenResponse.class, "/login/oauth/access_token")
                                .onWeb()
                                .withBody(body),
                        new GitHubCredential.OAuthClient())
                .body();
        if (response == null) {
            throw new IllegalStateException("GitHub answered a code exchange with no body");
        }
        if (response.error() != null) {
            String error = OAUTH_ERROR.matcher(response.error()).matches() ? response.error() : "unrecognized";
            throw new UserAuthorizationRejectedException(error);
        }
        if (response.accessToken() == null
                || response.accessToken().isBlank()
                || !"bearer".equals(response.tokenType())) {
            throw new IllegalStateException("GitHub answered a code exchange without a bearer token");
        }
        Instant expiresAt = response.expiresIn() == null || response.expiresIn() <= 0
                ? null
                : requestedAt.plusSeconds(response.expiresIn());
        return new ExchangedUserToken(new GitHubUserToken(response.accessToken()), expiresAt);
    }

    public GitHubUser currentUser(GitHubUserToken token) {
        GitHubUser user = asUser(token, GitHubRequest.get("user.get", GitHubUser.class, "/user"))
                .body();
        if (user == null
                || user.id() <= 0
                || user.login() == null
                || !LOGIN.matcher(user.login()).matches()) {
            throw new IllegalStateException("GitHub answered GET /user without a valid id and login");
        }
        return user;
    }

    /** @param page 1-based, as GitHub counts */
    public UserInstallations userInstallations(GitHubUserToken token, int page, int perPage) {
        requirePage(page, perPage);
        UserInstallations installations = asUser(
                        token,
                        GitHubRequest.get(
                                "user.installations.list",
                                UserInstallations.class,
                                "/user/installations?per_page={perPage}&page={page}",
                                perPage,
                                page))
                .body();
        if (installations == null || installations.installations() == null || installations.totalCount() < 0) {
            throw new IllegalStateException("GitHub answered GET /user/installations without a list");
        }
        return installations;
    }

    private static TokenScope checksWrite(long repoId) {
        return TokenScope.repository(repoId, Map.of("checks", TokenScope.Access.WRITE));
    }

    private static TokenScope contentsRead(long repoId) {
        return TokenScope.repository(repoId, Map.of("contents", TokenScope.Access.READ));
    }

    /** The {@code cursor} of the {@code rel="next"} link, or null when there is no next page or it names none. */
    static String nextCursor(String link) {
        if (link == null) {
            return null;
        }
        List<String> next = Arrays.stream(link.split(","))
                .filter(part -> Arrays.stream(part.split(";"))
                        .skip(1)
                        .anyMatch(p -> p.strip().equals(NEXT_RELATION)))
                .toList();
        if (next.isEmpty()) {
            return null;
        }
        String target = next.getFirst().split(";")[0].strip();
        if (!target.startsWith("<") || !target.endsWith(">")) {
            throw new IllegalStateException("GitHub answered with a malformed Link header");
        }
        String cursor = UriComponentsBuilder.fromUriString(target.substring(1, target.length() - 1))
                .build(true)
                .getQueryParams()
                .getFirst("cursor");
        if (cursor == null) {
            return null;
        }
        String decoded = URLDecoder.decode(cursor, StandardCharsets.UTF_8);
        if (!DELIVERY_CURSOR.matcher(decoded).matches()) {
            throw new IllegalStateException("GitHub answered with an unrecognized delivery log cursor");
        }
        return decoded;
    }

    private static void requireSha(String sha) {
        if (sha == null || !SHA.matcher(sha).matches()) {
            throw new IllegalArgumentException("A commit SHA must be 40 lowercase hex characters");
        }
    }

    private static void requirePage(int page, int perPage) {
        if (page < 1 || perPage < 1 || perPage > MAX_PER_PAGE) {
            throw new IllegalArgumentException("page must be at least 1 and perPage between 1 and " + MAX_PER_PAGE);
        }
    }

    private static InstallationRepositories requireRepositories(InstallationRepositories page, String endpoint) {
        if (page == null || page.repositories() == null || page.totalCount() < 0) {
            throw new IllegalStateException("GitHub answered " + endpoint + " without a list");
        }
        for (InstallationRepositories.Repository repository : page.repositories()) {
            if (repository == null
                    || repository.id() <= 0
                    || repository.fullName() == null
                    || !FULL_NAME.matcher(repository.fullName()).matches()
                    || repository.defaultBranch() == null
                    || repository.defaultBranch().isBlank()
                    || repository.defaultBranch().length() > MAX_BRANCH_LENGTH) {
                throw new IllegalStateException("GitHub answered " + endpoint + " with an invalid repository");
            }
        }
        return page;
    }

    <T> GitHubResponse<T> asApp(GitHubRequest<T> request) {
        return http.execute(request, new GitHubCredential.App(appJwt.current()));
    }

    <T> GitHubResponse<T> asInstallation(long installationId, TokenScope scope, GitHubRequest<T> request) {
        return tokens.withToken(
                installationId,
                scope,
                this::mintToken,
                token -> http.execute(request, new GitHubCredential.Installation(installationId, token)));
    }

    <T> GitHubResponse<T> asUser(GitHubUserToken token, GitHubRequest<T> request) {
        return http.execute(request, new GitHubCredential.User(token));
    }

    /** Separates a missing branch from a refused mint, which answers with the default not-found mapping. */
    private static final class BranchMissingException extends AppException {

        BranchMissingException(int status) {
            super(
                    HttpStatus.NOT_FOUND,
                    "GITHUB_BRANCH_NOT_FOUND",
                    "The branch was not found on GitHub.",
                    "GitHub answered " + status + " for a branch");
        }
    }

    /** Separates a missing account or collaborator from a refused mint, as {@link BranchMissingException} does. */
    private static final class AccountMissingException extends AppException {

        AccountMissingException(int status) {
            super(
                    HttpStatus.NOT_FOUND,
                    "GITHUB_ACCOUNT_NOT_FOUND",
                    "The account was not found on GitHub.",
                    "GitHub answered " + status + " for an account");
        }
    }

    /** Separates a commit GitHub no longer has from a refused mint, as {@link BranchMissingException} does. */
    private static final class CommitMissingException extends AppException {

        CommitMissingException(int status) {
            super(
                    HttpStatus.NOT_FOUND,
                    "GITHUB_COMMIT_NOT_FOUND",
                    "The commit was not found on GitHub.",
                    "GitHub answered " + status + " for a commit");
        }
    }
}
