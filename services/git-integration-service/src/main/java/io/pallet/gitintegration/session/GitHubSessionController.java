package io.pallet.gitintegration.session;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.gitintegration.docs.ApiDocs;
import io.pallet.gitintegration.security.AccessResolver;
import io.pallet.gitintegration.security.AuthorizationPurpose;
import io.pallet.gitintegration.session.SessionExceptions.GitHubSessionNotFoundException;
import io.pallet.gitintegration.session.dto.AuthorizationStartResponse;
import io.pallet.gitintegration.session.dto.CompleteAuthorizationRequest;
import io.pallet.gitintegration.session.dto.GitHubInstallationResponse;
import io.pallet.gitintegration.session.dto.GitHubSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
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
@Tag(
        name = "GitHub session",
        description = "The caller's own GitHub user session: authorizing Pallet on GitHub, reading and ending the "
                + "session, and listing the installations the caller's GitHub user can see. No response ever carries "
                + "a GitHub token.")
class GitHubSessionController {

    private final GitHubAuthorizationService authorizations;
    private final AccessResolver accessResolver;

    GitHubSessionController(GitHubAuthorizationService authorizations, AccessResolver accessResolver) {
        this.authorizations = authorizations;
        this.accessResolver = accessResolver;
    }

    @PostMapping("/authorizations")
    @Operation(
            summary = "Start GitHub authorization",
            description = "Returns authorizeUrl, GitHub's user authorization page with a signed, single-use state "
                    + "bound to the caller, and when that state expires. Send the user there; GitHub redirects back "
                    + "with code and state, which go to POST /github/authorizations/complete. A user who authorized "
                    + "before is sent straight back without a prompt. "
                    + ApiDocs.ANY_ACCOUNT,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "201",
                        description = "Authorization started")
            })
    ResponseEntity<ApiResponse<AuthorizationStartResponse>> start() {
        AuthorizationStartResponse started = authorizations.start(caller());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("GitHub authorization started", started));
    }

    @PostMapping("/authorizations/complete")
    @Operation(
            summary = "Complete GitHub authorization",
            description = "Takes code and state from GitHub's redirect, exchanges the code and stores the session. "
                    + "Each state completes once; start again after a 400. "
                    + ApiDocs.ANY_ACCOUNT,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "200",
                        description = "Session stored"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "400",
                        description = "INVALID_AUTHORIZATION_STATE: the state is forged, expired, already used or for "
                                + "another user, or GitHub rejected the code. Also a missing code or state",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "502",
                        description = ApiDocs.GITHUB_502,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<GitHubSessionResponse> complete(@Valid @RequestBody CompleteAuthorizationRequest request) {
        GitHubUserSession session = authorizations.complete(
                caller(), request.code(), request.state(), AuthorizationPurpose.AUTHORIZE, null);
        return ApiResponse.ok("GitHub authorization completed", view(session));
    }

    @GetMapping("/session")
    @Operation(
            summary = "Get the caller's GitHub session",
            description = "The GitHub login and when the session expires. " + ApiDocs.ANY_ACCOUNT,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The session"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = "GITHUB_SESSION_NOT_FOUND: the caller has no session, or it expired",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<GitHubSessionResponse> session() {
        GitHubUserSession session =
                authorizations.findSession(caller()).orElseThrow(GitHubSessionNotFoundException::new);
        return ApiResponse.ok("GitHub session retrieved", view(session));
    }

    @DeleteMapping("/session")
    @Operation(
            summary = "End the caller's GitHub session",
            description = "Forgets the session on Pallet's side; the app stays authorized on GitHub, so the next "
                    + "authorization needs no prompt. Ending a session that doesn't exist is also a 204. "
                    + ApiDocs.ANY_ACCOUNT,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Ended")
            })
    ResponseEntity<Void> end() {
        authorizations.endSession(caller());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/installations")
    @Operation(
            summary = "List installations the caller's GitHub user can see",
            description = "What the dashboard offers when an org links an installation that already exists: pick one "
                    + "here, then call POST /orgs/{orgId}/github/installations with {installationId} alone. Each entry "
                    + "says whether the app is suspended on that account. "
                    + ApiDocs.ANY_ACCOUNT
                    + ApiDocs.SESSION,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "200",
                        description = "One page of installations"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = "GITHUB_AUTHORIZATION_REQUIRED",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "502",
                        description = ApiDocs.GITHUB_502,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<PageResponse<GitHubInstallationResponse>> installations(
            @ParameterObject @ModelAttribute PageQuery pageQuery) {
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
