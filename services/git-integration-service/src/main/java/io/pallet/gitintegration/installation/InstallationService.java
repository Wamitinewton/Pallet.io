package io.pallet.gitintegration.installation;

import io.pallet.common.api.PageResponse;
import io.pallet.common.outbox.OutboxWriter;
import io.pallet.gitintegration.audit.Actor;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubNotFoundException;
import io.pallet.gitintegration.installation.InstallationExceptions.InstallationNotAccessibleException;
import io.pallet.gitintegration.installation.InstallationExceptions.InstallationSuspendedException;
import io.pallet.gitintegration.installation.dto.InstallSessionResponse;
import io.pallet.gitintegration.installation.dto.InstallationLinkResponse;
import io.pallet.gitintegration.installation.dto.LinkInstallationRequest;
import io.pallet.gitintegration.repolink.RepoLink.DisconnectReason;
import io.pallet.gitintegration.scm.ScmProvider;
import io.pallet.gitintegration.scm.ScmProvider.ScmInstallation;
import io.pallet.gitintegration.security.AccessExceptions.InstallationNotFoundException;
import io.pallet.gitintegration.security.AuthorizationPurpose;
import io.pallet.gitintegration.security.AuthorizationStateTokens;
import io.pallet.gitintegration.security.AuthorizationStateTokens.IssuedState;
import io.pallet.gitintegration.session.GitHubAuthorizationService;
import io.pallet.gitintegration.session.GitHubUserSession;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Connecting an org to a GitHub installation (ARCHITECTURE.md §Connecting an installation). Every GitHub call happens
 * before the transaction opens; the transaction re-reads nothing from GitHub and holds no lock across a network call.
 */
@Service
public class InstallationService {

    static final Sort LINK_ORDER = Sort.by(Sort.Order.desc("linkedAt"), Sort.Order.asc("key.installationId"));

    public record LinkResult(InstallationLinkResponse link, boolean created) {}

    private final AuthorizationStateTokens states;
    private final GitHubAuthorizationService authorizations;
    private final ScmProvider scm;
    private final InstallationRepository installations;
    private final InstallationLinkRepository links;
    private final ConnectionTeardown teardown;
    private final UnusedInstallationNotifier unusedNotifier;
    private final RepositorySync repositorySync;
    private final OutboxWriter outbox;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final Clock clock;
    private final URI webBaseUrl;
    private final String appSlug;
    private final int maxInstallationPages;

    InstallationService(
            AuthorizationStateTokens states,
            GitHubAuthorizationService authorizations,
            ScmProvider scm,
            InstallationRepository installations,
            InstallationLinkRepository links,
            ConnectionTeardown teardown,
            UnusedInstallationNotifier unusedNotifier,
            RepositorySync repositorySync,
            OutboxWriter outbox,
            PlatformTransactionManager transactionManager,
            JsonMapper json,
            Clock clock,
            GitIntegrationProperties properties) {
        this.states = states;
        this.authorizations = authorizations;
        this.scm = scm;
        this.installations = installations;
        this.links = links;
        this.teardown = teardown;
        this.unusedNotifier = unusedNotifier;
        this.repositorySync = repositorySync;
        this.outbox = outbox;
        this.transaction = new TransactionTemplate(transactionManager);
        this.json = json;
        this.clock = clock;
        this.webBaseUrl = properties.github().webBaseUrl();
        this.appSlug = properties.github().appSlug();
        this.maxInstallationPages = properties.link().maxInstallationPages();
    }

    /** Issues an {@code INSTALL} state bound to this org and caller, and the GitHub page that installs the app. */
    public InstallSessionResponse startInstall(String orgId, String sub) {
        IssuedState state = states.issue(AuthorizationPurpose.INSTALL, sub, orgId);
        String installUrl = UriComponentsBuilder.fromUri(webBaseUrl)
                .path("/apps/{slug}/installations/new")
                .queryParam("state", state.state())
                .encode()
                .buildAndExpand(appSlug)
                .toUriString();
        return new InstallSessionResponse(installUrl, state.expiresAt());
    }

    /**
     * Links an installation after proving, with the caller's own GitHub token, that they can see it.
     *
     * @throws InstallationNotAccessibleException if the caller's GitHub user can't see the installation
     * @throws InstallationSuspendedException if the installation is suspended on GitHub
     */
    public LinkResult link(String orgId, String sub, LinkInstallationRequest request) {
        long installationId = request.installationId();
        GitHubUserSession session = request.isFreshInstall()
                ? authorizations.complete(sub, request.code(), request.state(), AuthorizationPurpose.INSTALL, orgId)
                : authorizations.requireSession(sub);
        if (!authorizations.canSeeInstallation(sub, session, installationId, maxInstallationPages)) {
            throw new InstallationNotAccessibleException();
        }
        ScmInstallation installation = fetch(installationId);
        if (installation.suspended()) {
            throw new InstallationSuspendedException();
        }
        String permissions = json.writeValueAsString(installation.permissions());
        return Objects.requireNonNull(transaction.execute(status -> {
            installations.upsertLinked(
                    installationId,
                    installation.accountId(),
                    installation.accountLogin(),
                    installation.accountType(),
                    installation.repositorySelection(),
                    permissions);
            boolean created = links.activate(orgId, installationId, sub, session.githubUserId()) == 1;
            if (created) {
                outbox.append(AuditEvents.installationLinked(
                        orgId,
                        sub,
                        installationId,
                        installation.accountLogin(),
                        session.githubUserId(),
                        clock.instant()));
                repositorySync.scheduleAfterCommit(installationId);
            }
            return new LinkResult(view(orgId, installationId), created);
        }));
    }

    public PageResponse<InstallationLinkResponse> list(String orgId, Pageable requested) {
        Page<InstallationLink> page = links.findByStatus(
                orgId,
                InstallationLink.Status.ACTIVE,
                PageRequest.of(requested.getPageNumber(), requested.getPageSize(), LINK_ORDER));
        Map<Long, Installation> byId =
                installations
                        .findAllById(page.getContent().stream()
                                .map(InstallationLink::installationId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(Installation::installationId, Function.identity()));
        return PageResponse.of(page, link -> view(link, byId.get(link.installationId())));
    }

    /**
     * Ends this org's link only; other orgs' links and repo links to the same installation are untouched. When it was
     * the last link, this org's admins are reminded that the installation will be uninstalled.
     *
     * @throws InstallationNotFoundException if this org has no active link to the installation
     */
    public void unlink(String orgId, long installationId, String sub) {
        transaction.executeWithoutResult(status -> unusedNotifier.remind(teardown.unlinkInstallation(
                orgId, installationId, DisconnectReason.INSTALLATION_UNLINKED, Actor.user(sub))));
    }

    /** GitHub's own record says nothing the caller couldn't already see, so a missing one looks the same as hidden. */
    private ScmInstallation fetch(long installationId) {
        try {
            return scm.installation(installationId);
        } catch (GitHubNotFoundException e) {
            throw new InstallationNotAccessibleException();
        }
    }

    private InstallationLinkResponse view(String orgId, long installationId) {
        InstallationLink link = links.findLink(orgId, installationId).orElseThrow(InstallationNotFoundException::new);
        Installation installation =
                installations.findById(installationId).orElseThrow(InstallationNotFoundException::new);
        return view(link, installation);
    }

    private static InstallationLinkResponse view(InstallationLink link, Installation installation) {
        return new InstallationLinkResponse(
                link.installationId(),
                installation == null ? null : installation.accountLogin(),
                installation == null ? null : installation.accountType(),
                installation == null ? null : installation.status().name(),
                link.linkedByUserId(),
                link.linkedAt());
    }
}
