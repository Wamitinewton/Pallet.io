package io.pallet.gitintegration.session;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.api.PageResponse;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.github.ExchangedUserToken;
import io.pallet.gitintegration.github.GitHubClient;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubNotFoundException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubUserTokenRejectedException;
import io.pallet.gitintegration.github.GitHubExceptions.UserAuthorizationRejectedException;
import io.pallet.gitintegration.github.dto.GitHubUser;
import io.pallet.gitintegration.github.dto.InstallationRepositories;
import io.pallet.gitintegration.github.dto.Repository;
import io.pallet.gitintegration.github.dto.UserInstallations;
import io.pallet.gitintegration.security.AccessExceptions.InvalidAuthorizationStateException;
import io.pallet.gitintegration.security.AuthorizationPurpose;
import io.pallet.gitintegration.security.AuthorizationStateTokens;
import io.pallet.gitintegration.security.AuthorizationStateTokens.IssuedState;
import io.pallet.gitintegration.session.SessionExceptions.GitHubAuthorizationRequiredException;
import io.pallet.gitintegration.session.UserRepositories.UserRepository;
import io.pallet.gitintegration.session.dto.AuthorizationStartResponse;
import io.pallet.gitintegration.session.dto.GitHubInstallationResponse;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * GitHub's user authorization flow and the session it produces (ARCHITECTURE.md §GitHub user sessions). No method holds
 * a database transaction across a GitHub call: the state is consumed in its own statement before the exchange.
 */
@Service
public class GitHubAuthorizationService {

    public static final String SESSIONS_CREATED = "git.sessions.created";

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final AuthorizationStateTokens states;
    private final GitHubClient github;
    private final GitHubUserSessionStore sessions;
    private final Clock clock;
    private final MeterRegistry meters;
    private final URI webBaseUrl;
    private final String clientId;
    private final Duration maxTtl;

    GitHubAuthorizationService(
            AuthorizationStateTokens states,
            GitHubClient github,
            GitHubUserSessionStore sessions,
            Clock clock,
            MeterRegistry meters,
            GitIntegrationProperties properties) {
        this.states = states;
        this.github = github;
        this.sessions = sessions;
        this.clock = clock;
        this.meters = meters;
        this.webBaseUrl = properties.github().webBaseUrl();
        this.clientId = properties.github().clientId();
        this.maxTtl = properties.userSession().maxTtl();
    }

    /** No {@code redirect_uri}: GitHub sends the user back to the app's configured callback, which no caller can change. */
    public AuthorizationStartResponse start(String sub) {
        IssuedState state = states.issue(AuthorizationPurpose.AUTHORIZE, sub, null);
        String authorizeUrl = UriComponentsBuilder.fromUri(webBaseUrl)
                .path("/login/oauth/authorize")
                .queryParam("client_id", clientId)
                .queryParam("state", state.state())
                .encode()
                .toUriString();
        return new AuthorizationStartResponse(authorizeUrl, state.expiresAt());
    }

    /**
     * Consumes the state, exchanges the code, identifies the GitHub user, and stores the session.
     *
     * @throws InvalidAuthorizationStateException if the state or the code is rejected
     */
    public GitHubUserSession complete(
            String sub, String code, String state, AuthorizationPurpose purpose, String orgId) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new InvalidAuthorizationStateException();
        }
        states.consume(state, purpose, sub, orgId);
        ExchangedUserToken exchanged;
        try {
            exchanged = github.exchangeCode(code);
        } catch (UserAuthorizationRejectedException e) {
            throw new InvalidAuthorizationStateException();
        }
        GitHubUser user;
        try {
            user = github.currentUser(exchanged.token());
        } catch (GitHubUserTokenRejectedException e) {
            throw new GitHubAuthorizationRequiredException();
        }
        Instant cap = clock.instant().plus(maxTtl);
        Instant expiresAt =
                exchanged.expiresAt() == null || exchanged.expiresAt().isAfter(cap) ? cap : exchanged.expiresAt();
        GitHubUserSession session = new GitHubUserSession(exchanged.token(), user.id(), user.login(), expiresAt);
        sessions.save(sub, session);
        meters.counter(SESSIONS_CREATED, "purpose", purpose.name().toLowerCase(Locale.ROOT))
                .increment();
        return session;
    }

    public Optional<GitHubUserSession> findSession(String sub) {
        return sessions.find(sub);
    }

    public GitHubUserSession requireSession(String sub) {
        return sessions.find(sub).orElseThrow(GitHubAuthorizationRequiredException::new);
    }

    public void endSession(String sub) {
        sessions.delete(sub);
    }

    /** The installations the caller's GitHub user can see, one page of {@code GET /user/installations}. */
    public PageResponse<GitHubInstallationResponse> installations(String sub, Pageable pageable) {
        GitHubUserSession session = requireSession(sub);
        int size = Math.min(pageable.getPageSize(), GitHubClient.MAX_PER_PAGE);
        UserInstallations page =
                asUser(sub, () -> github.userInstallations(session.token(), pageable.getPageNumber() + 1, size));
        List<GitHubInstallationResponse> content = page.installations().stream()
                .map(installation -> new GitHubInstallationResponse(
                        installation.id(),
                        installation.account() == null
                                ? null
                                : installation.account().login(),
                        installation.account() == null
                                ? null
                                : installation.account().type(),
                        installation.suspended()))
                .toList();
        return PageResponse.of(
                new PageImpl<>(content, PageRequest.of(pageable.getPageNumber(), size), page.totalCount()));
    }

    /**
     * Walks {@code GET /user/installations} with the session's own token until {@code installationId} appears. This is
     * the proof that the caller may link it: an id typed into a request proves nothing.
     */
    public boolean canSeeInstallation(String sub, GitHubUserSession session, long installationId, int maxPages) {
        for (int page = 1; page <= maxPages; page++) {
            int current = page;
            UserInstallations listed =
                    asUser(sub, () -> github.userInstallations(session.token(), current, GitHubClient.MAX_PER_PAGE));
            if (listed.installations().stream().anyMatch(installation -> installation.id() == installationId)) {
                return true;
            }
            if (listed.installations().size() < GitHubClient.MAX_PER_PAGE
                    || (long) current * GitHubClient.MAX_PER_PAGE >= listed.totalCount()) {
                return false;
            }
        }
        return false;
    }

    /**
     * One page of {@code GET /user/installations/{id}/repositories}: only what both the user and the installation
     * reach, never the installation's whole list.
     *
     * @param page 1-based, as GitHub counts
     */
    public UserRepositories userRepositories(
            String sub, GitHubUserSession session, long installationId, int page, int perPage) {
        InstallationRepositories listed =
                asUser(sub, () -> github.userInstallationRepositories(session.token(), installationId, page, perPage));
        return new UserRepositories(
                listed.totalCount(),
                listed.repositories().stream()
                        .map(repository -> new UserRepository(
                                repository.id(),
                                repository.fullName(),
                                repository.defaultBranch(),
                                repository.isPrivate(),
                                repository.archived(),
                                highest(repository.permissions())))
                        .toList());
    }

    /**
     * One repository as the caller's own token sees it. GitHub answers only when both the user and some installation
     * of the app reach it.
     *
     * @return empty when GitHub hides it from the caller
     */
    public Optional<RepositoryAccess> repository(String sub, GitHubUserSession session, long repoId) {
        Repository repository;
        try {
            repository = asUser(sub, () -> github.repository(session.token(), repoId));
        } catch (GitHubNotFoundException e) {
            return Optional.empty();
        }
        return Optional.of(new RepositoryAccess(
                repository.id(),
                repository.owner().id(),
                repository.fullName(),
                repository.defaultBranch(),
                repository.archived(),
                highest(repository.permissions())));
    }

    private static String highest(InstallationRepositories.Permissions permissions) {
        if (permissions == null) {
            return null;
        }
        if (permissions.admin()) {
            return "admin";
        }
        if (permissions.maintain()) {
            return "maintain";
        }
        if (permissions.push()) {
            return "push";
        }
        if (permissions.triage()) {
            return "triage";
        }
        return permissions.pull() ? "pull" : null;
    }

    /** A token GitHub no longer accepts ends the session at once, so the next request doesn't try it again. */
    private <T> T asUser(String sub, Supplier<T> call) {
        try {
            return call.get();
        } catch (GitHubUserTokenRejectedException e) {
            sessions.delete(sub);
            throw new GitHubAuthorizationRequiredException();
        }
    }
}
