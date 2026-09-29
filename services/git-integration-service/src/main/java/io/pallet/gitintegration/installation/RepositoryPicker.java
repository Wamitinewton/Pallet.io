package io.pallet.gitintegration.installation;

import io.pallet.common.api.PageResponse;
import io.pallet.common.error.BadRequestException;
import io.pallet.gitintegration.access.RepoPermission;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.github.GitHubClient;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubNotFoundException;
import io.pallet.gitintegration.installation.InstallationExceptions.InstallationNotAccessibleException;
import io.pallet.gitintegration.installation.dto.PickerRepositoryResponse;
import io.pallet.gitintegration.security.ResourceScope;
import io.pallet.gitintegration.session.GitHubAuthorizationService;
import io.pallet.gitintegration.session.GitHubUserSession;
import io.pallet.gitintegration.session.UserRepositories;
import io.pallet.gitintegration.session.UserRepositories.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * The repositories a caller may pick from an installation: exactly what their own token returns from
 * {@code GET /user/installations/{id}/repositories}, never the installation read model, which reaches repositories this
 * user may not. GitHub has no name filter there, so {@code q} scans at most {@code link.max-picker-pages} pages.
 */
@Component
public class RepositoryPicker {

    static final int MAX_QUERY_LENGTH = 100;

    private final ResourceScope scope;
    private final GitHubAuthorizationService authorizations;
    private final RepoPermission minPermission;
    private final int maxPickerPages;

    RepositoryPicker(
            ResourceScope scope, GitHubAuthorizationService authorizations, GitIntegrationProperties properties) {
        this.scope = scope;
        this.authorizations = authorizations;
        this.minPermission = properties.link().minRepoPermission();
        this.maxPickerPages = properties.link().maxPickerPages();
    }

    public PageResponse<PickerRepositoryResponse> list(
            String orgId, String sub, long installationId, String q, Pageable pageable) {
        scope.requireInstallation(orgId, installationId);
        if (q != null && q.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("q must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        GitHubUserSession session = authorizations.requireSession(sub);
        PageRequest page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        try {
            return q == null || q.isBlank()
                    ? onePage(sub, session, installationId, page)
                    : filtered(sub, session, installationId, q.strip().toLowerCase(Locale.ROOT), page);
        } catch (GitHubNotFoundException e) {
            throw new InstallationNotAccessibleException();
        }
    }

    private PageResponse<PickerRepositoryResponse> onePage(
            String sub, GitHubUserSession session, long installationId, PageRequest page) {
        UserRepositories listed = authorizations.userRepositories(
                sub, session, installationId, page.getPageNumber() + 1, page.getPageSize());
        return PageResponse.of(
                new PageImpl<>(listed.repositories().stream().map(this::view).toList(), page, listed.totalCount()));
    }

    private PageResponse<PickerRepositoryResponse> filtered(
            String sub, GitHubUserSession session, long installationId, String prefix, PageRequest page) {
        List<PickerRepositoryResponse> matches = new ArrayList<>();
        for (int githubPage = 1; githubPage <= maxPickerPages; githubPage++) {
            UserRepositories listed = authorizations.userRepositories(
                    sub, session, installationId, githubPage, GitHubClient.MAX_PER_PAGE);
            listed.repositories().stream()
                    .filter(repository ->
                            repository.name().toLowerCase(Locale.ROOT).startsWith(prefix))
                    .map(this::view)
                    .forEach(matches::add);
            if (listed.repositories().size() < GitHubClient.MAX_PER_PAGE
                    || (long) githubPage * GitHubClient.MAX_PER_PAGE >= listed.totalCount()) {
                break;
            }
        }
        int from = (int) Math.min(page.getOffset(), matches.size());
        int to = Math.min(from + page.getPageSize(), matches.size());
        return PageResponse.of(new PageImpl<>(matches.subList(from, to), page, matches.size()));
    }

    private PickerRepositoryResponse view(UserRepository repository) {
        return new PickerRepositoryResponse(
                repository.id(),
                repository.fullName(),
                repository.defaultBranch(),
                repository.isPrivate(),
                repository.archived(),
                repository.permission(),
                !repository.archived()
                        && RepoPermission.fromWireName(repository.permission())
                                .filter(held -> held.atLeast(minPermission))
                                .isPresent());
    }
}
