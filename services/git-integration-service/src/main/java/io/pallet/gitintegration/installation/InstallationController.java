package io.pallet.gitintegration.installation;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.gitintegration.docs.ApiDocs;
import io.pallet.gitintegration.installation.InstallationService.LinkResult;
import io.pallet.gitintegration.installation.dto.InstallSessionResponse;
import io.pallet.gitintegration.installation.dto.InstallationLinkResponse;
import io.pallet.gitintegration.installation.dto.LinkInstallationRequest;
import io.pallet.gitintegration.installation.dto.PickerRepositoryResponse;
import io.pallet.gitintegration.security.AccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * An org's GitHub installations. {@code setup_action=request} (a GitHub organization member asked their admin to
 * approve the install) comes back with no {@code installation_id}; the dashboard waits and calls nothing here.
 */
@RestController
@RequestMapping("/git-integration/orgs/{orgId}/github")
@Tag(
        name = "Installations",
        description = "Connecting an org to GitHub App installations. A fresh install: POST install-sessions, send "
                + "the user to installUrl, and GitHub's setup redirect brings back installation_id, code and state; "
                + "POST installations with all three. A setup redirect with setup_action=request carries no "
                + "installation_id: a GitHub organization member asked their admin to approve the install, and there "
                + "is nothing to call until the admin does. An installation that already exists: GET "
                + "/github/installations, then POST installations with {installationId} alone.")
class InstallationController {

    private final InstallationService installations;
    private final RepositoryPicker picker;
    private final AccessResolver accessResolver;

    InstallationController(InstallationService installations, RepositoryPicker picker, AccessResolver accessResolver) {
        this.installations = installations;
        this.picker = picker;
        this.accessResolver = accessResolver;
    }

    @PostMapping("/install-sessions")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Start a GitHub App install",
            description = "Returns installUrl, GitHub's page for installing the app with a signed, single-use state "
                    + "bound to this org and the caller, and when the state expires (ten minutes). After the install, "
                    + "GitHub redirects back with installation_id, code and state for POST installations. "
                    + ApiDocs.ADMIN
                    + ApiDocs.NO_SESSION,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "201",
                        description = "Install session started"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ResponseEntity<ApiResponse<InstallSessionResponse>> startInstall(@PathVariable String orgId) {
        InstallSessionResponse started = installations.startInstall(orgId, caller(orgId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Install session started", started));
    }

    @PostMapping("/installations")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Link an installation to the org",
            description = "Either {installationId, code, state} straight from a fresh install's setup redirect, which "
                    + "also creates the caller's GitHub session, or {installationId} alone for an installation that "
                    + "already exists, which needs a session. Both check with the caller's own GitHub token that "
                    + "they can see the installation. 201 with the link, 200 when this org already had it linked. "
                    + ApiDocs.ADMIN
                    + ApiDocs.SESSION
                    + " The fresh-install form brings its own session.",
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Linked"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "200",
                        description = "Already linked to this org"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "400",
                        description = "INVALID_AUTHORIZATION_STATE, or code and state not sent together",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description =
                                "INSTALLATION_NOT_ACCESSIBLE, GITHUB_AUTHORIZATION_REQUIRED, " + ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "INSTALLATION_SUSPENDED: the app is suspended on that GitHub account",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "502",
                        description = ApiDocs.GITHUB_502,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "503",
                        description = ApiDocs.GITHUB_503,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ResponseEntity<ApiResponse<InstallationLinkResponse>> link(
            @PathVariable String orgId, @Valid @RequestBody LinkInstallationRequest request) {
        LinkResult result = installations.link(orgId, caller(orgId), request);
        return result.created()
                ? ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Installation linked", result.link()))
                : ResponseEntity.ok(ApiResponse.ok("Installation already linked", result.link()));
    }

    @GetMapping("/installations")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(
            summary = "List the org's installations",
            description = "The org's linked installations and their status on GitHub, newest link first; sort is "
                    + "ignored. "
                    + ApiDocs.ANY_MEMBER
                    + ApiDocs.NO_SESSION,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "200",
                        description = "One page of linked installations"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<PageResponse<InstallationLinkResponse>> list(
            @PathVariable String orgId, @ParameterObject @ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok(
                "Installations retrieved",
                installations.list(orgId, pageQuery.toPageable(InstallationService.LINK_ORDER)));
    }

    @DeleteMapping("/installations/{installationId}")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Unlink an installation from the org",
            description = "Unlinks it from this org only and disconnects this org's repository links that used it; "
                    + "other orgs keep theirs. The app stays installed on GitHub. "
                    + ApiDocs.ADMIN
                    + ApiDocs.NO_SESSION,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Unlinked"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = "INSTALLATION_NOT_FOUND: not linked to this org. " + ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ResponseEntity<Void> unlink(@PathVariable String orgId, @PathVariable long installationId) {
        installations.unlink(orgId, installationId, caller(orgId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/installations/{installationId}/repositories")
    @PreAuthorize("@access.atLeast(#orgId, 'DEVELOPER')")
    @Operation(
            summary = "List repositories the caller can link",
            description = "The repository picker. Lists only repositories in this installation that the caller can "
                    + "reach on GitHub, with the caller's own permission and whether PUT repo-link would accept it; "
                    + "never the installation's full list. "
                    + ApiDocs.DEVELOPER
                    + ApiDocs.SESSION,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "200",
                        description = "One page of repositories"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "400",
                        description = "q is longer than 100 characters",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description =
                                "INSTALLATION_NOT_ACCESSIBLE, GITHUB_AUTHORIZATION_REQUIRED, " + ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = "INSTALLATION_NOT_FOUND: not linked to this org. " + ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "502",
                        description = ApiDocs.GITHUB_502,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<PageResponse<PickerRepositoryResponse>> repositories(
            @PathVariable String orgId,
            @PathVariable long installationId,
            @Parameter(description = "Filters by repository name prefix, at most 100 characters.")
                    @RequestParam(required = false)
                    String q,
            @ParameterObject @ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok(
                "Repositories retrieved",
                picker.list(orgId, caller(orgId), installationId, q, pageQuery.toPageable(Sort.unsorted())));
    }

    private String caller(String orgId) {
        return accessResolver.resolve(orgId).userId();
    }
}
