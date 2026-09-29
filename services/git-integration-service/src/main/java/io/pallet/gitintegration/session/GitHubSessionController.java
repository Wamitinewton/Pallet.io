package io.pallet.gitintegration.session;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.gitintegration.security.AccessResolver;
import io.pallet.gitintegration.security.AuthorizationPurpose;
import io.pallet.gitintegration.session.SessionExceptions.GitHubSessionNotFoundException;
import io.pallet.gitintegration.session.dto.AuthorizationStartResponse;
import io.pallet.gitintegration.session.dto.CompleteAuthorizationRequest;
import io.pallet.gitintegration.session.dto.GitHubInstallationResponse;
import io.pallet.gitintegration.session.dto.GitHubSessionResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own GitHub session. Not org-scoped: the subject is the token's {@code sub}, whichever org is open. */
@RestController
@RequestMapping("/git-integration/github")
class GitHubSessionController {

    private final GitHubAuthorizationService authorizations;
    private final AccessResolver accessResolver;

    GitHubSessionController(GitHubAuthorizationService authorizations, AccessResolver accessResolver) {
        this.authorizations = authorizations;
        this.accessResolver = accessResolver;
    }

    @PostMapping("/authorizations")
    ResponseEntity<ApiResponse<AuthorizationStartResponse>> start() {
        AuthorizationStartResponse started = authorizations.start(caller());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("GitHub authorization started", started));
    }

    @PostMapping("/authorizations/complete")
    ApiResponse<GitHubSessionResponse> complete(@Valid @RequestBody CompleteAuthorizationRequest request) {
        GitHubUserSession session = authorizations.complete(
                caller(), request.code(), request.state(), AuthorizationPurpose.AUTHORIZE, null);
        return ApiResponse.ok("GitHub authorization completed", view(session));
    }

    @GetMapping("/session")
    ApiResponse<GitHubSessionResponse> session() {
        GitHubUserSession session =
                authorizations.findSession(caller()).orElseThrow(GitHubSessionNotFoundException::new);
        return ApiResponse.ok("GitHub session retrieved", view(session));
    }

    @DeleteMapping("/session")
    ResponseEntity<Void> end() {
        authorizations.endSession(caller());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/installations")
    ApiResponse<PageResponse<GitHubInstallationResponse>> installations(@ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok(
                "GitHub installations retrieved",
                authorizations.installations(caller(), pageQuery.toPageable(Sort.unsorted())));
    }

    private String caller() {
        return accessResolver.caller().userId();
    }

    private static GitHubSessionResponse view(GitHubUserSession session) {
        return new GitHubSessionResponse(session.githubLogin(), session.expiresAt());
    }
}
