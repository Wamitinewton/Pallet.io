package io.pallet.gitintegration.repolink;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.gitintegration.docs.ApiDocs;
import io.pallet.gitintegration.repolink.dto.ManualBuildRequestDto;
import io.pallet.gitintegration.repolink.dto.ManualBuildResultDto;
import io.pallet.gitintegration.repolink.dto.PatchRepoLinkRequest;
import io.pallet.gitintegration.repolink.dto.PutRepoLinkRequest;
import io.pallet.gitintegration.repolink.dto.RepoLinkDto;
import io.pallet.gitintegration.security.AccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An app's repository link. Choosing the repository needs ADMIN and the caller's own GitHub access; tuning it needs
 * DEVELOPER and neither a session nor a check, since it can't change which repository is built.
 */
@RestController
@RequestMapping("/git-integration/orgs/{orgId}/apps/{appId}/repo-link")
@Tag(
        name = "Repository links",
        description = "Which repository and branch an app deploys from. Choosing the repository needs ADMIN and the "
                + "caller's own push access on GitHub; tuning branch, directory and auto-deploy needs DEVELOPER and "
                + "no GitHub session, since it can't change which repository is built.")
class RepoLinkController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RepoLinkService links;
    private final ManualBuildService builds;
    private final AccessResolver accessResolver;

    RepoLinkController(RepoLinkService links, ManualBuildService builds, AccessResolver accessResolver) {
        this.links = links;
        this.builds = builds;
        this.accessResolver = accessResolver;
    }

    @PutMapping
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Link a repository to the app",
            description =
                    "Links the app to a repository in one of this org's installations. The caller must hold at least "
                            + "push on it on GitHub, checked with their own token. The branch defaults to the repository's "
                            + "default branch and must exist. autoDeploy and deployNow are separate on purpose: autoDeploy "
                            + "says whether future pushes build, deployNow whether to build the current head right away. "
                            + "The dashboard's import flow sends deployNow true; linking a repository to test the connection "
                            + "gets no deploy. "
                            + ApiDocs.ADMIN
                            + ApiDocs.SESSION,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Linked"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "400",
                        description = "INVALID_ROOT_DIRECTORY, an invalid productionBranch, or an unknown field",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description =
                                "REPOSITORY_NOT_ACCESSIBLE, REPOSITORY_PERMISSION_TOO_LOW, GITHUB_AUTHORIZATION_REQUIRED, "
                                        + ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = "APP_NOT_FOUND, INSTALLATION_NOT_FOUND, " + ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "REPO_LINK_EXISTS: the app already has an active link. INSTALLATION_SUSPENDED",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "422",
                        description = "REPOSITORY_ARCHIVED or BRANCH_NOT_FOUND",
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
    ResponseEntity<ApiResponse<RepoLinkDto>> link(
            @PathVariable String orgId, @PathVariable UUID appId, @Valid @RequestBody PutRepoLinkRequest request) {
        RepoLinkDto link = links.link(orgId, appId, caller(orgId), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Repository linked", link));
    }

    @GetMapping
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(
            summary = "Get the app's repository link",
            description = "The link, its status and disconnect reason, the last accepted head, who verified access and "
                    + "when it was last confirmed, and warnings such as REPOSITORY_ARCHIVED. "
                    + ApiDocs.ANY_MEMBER
                    + ApiDocs.NO_SESSION,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The link"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = "APP_NOT_FOUND, REPO_LINK_NOT_FOUND, " + ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<RepoLinkDto> get(@PathVariable String orgId, @PathVariable UUID appId) {
        return ApiResponse.ok("Repository link retrieved", links.get(orgId, appId));
    }

    @PatchMapping
    @PreAuthorize("@access.atLeast(#orgId, 'DEVELOPER')")
    @Operation(
            summary = "Tune the repository link",
            description =
                    "Changes productionBranch, rootDirectory or autoDeploy, with the version from the last read. An "
                            + "absent field is unchanged; an empty rootDirectory builds from the repository root. A new "
                            + "branch must exist on GitHub. "
                            + ApiDocs.DEVELOPER
                            + ApiDocs.NO_SESSION,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "400",
                        description =
                                "INVALID_ROOT_DIRECTORY, an invalid productionBranch, no field to change, or any other field",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = "APP_NOT_FOUND, REPO_LINK_NOT_FOUND, " + ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "CONCURRENT_MODIFICATION: version is stale",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "422",
                        description = "BRANCH_NOT_FOUND",
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
    ApiResponse<RepoLinkDto> update(
            @PathVariable String orgId, @PathVariable UUID appId, @Valid @RequestBody PatchRepoLinkRequest request) {
        return ApiResponse.ok("Repository link updated", links.update(orgId, appId, caller(orgId), request));
    }

    @PostMapping("/verification")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Take over verifying the link",
            description =
                    "Makes the caller the link's verifier after the same access check as linking, for handing over "
                            + "before the current verifier leaves the GitHub organization. A caller who fails the check "
                            + "changes nothing. "
                            + ApiDocs.ADMIN
                            + ApiDocs.SESSION,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "200",
                        description = "The caller is now the verifier"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description =
                                "REPOSITORY_NOT_ACCESSIBLE, REPOSITORY_PERMISSION_TOO_LOW, GITHUB_AUTHORIZATION_REQUIRED, "
                                        + ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description =
                                "APP_NOT_FOUND, REPO_LINK_NOT_FOUND, INSTALLATION_NOT_FOUND, " + ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "CONCURRENT_MODIFICATION: the link moved to another repository during the check. "
                                + "INSTALLATION_SUSPENDED",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "422",
                        description = "REPOSITORY_ARCHIVED or BRANCH_NOT_FOUND",
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
    ApiResponse<RepoLinkDto> takeOverVerification(@PathVariable String orgId, @PathVariable UUID appId) {
        return ApiResponse.ok("Repository access verified", links.takeOverVerification(orgId, appId, caller(orgId)));
    }

    @DeleteMapping
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Disconnect the repository link",
            description = "Sets the link DISCONNECTED with reason UNLINKED_BY_USER; pushes stop building. "
                    + ApiDocs.ADMIN
                    + ApiDocs.NO_SESSION,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "204",
                        description = "Disconnected"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description = "APP_NOT_FOUND, REPO_LINK_NOT_FOUND, " + ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ResponseEntity<Void> disconnect(@PathVariable String orgId, @PathVariable UUID appId) {
        links.disconnect(orgId, appId, caller(orgId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/builds")
    @PreAuthorize("@access.atLeast(#orgId, 'DEVELOPER')")
    @Operation(
            summary = "Build the branch's current head",
            description = "Resolves the branch's head on GitHub and asks for a build of it; the branch defaults to the "
                    + "production branch and a commit can't be named. A retry with the same Idempotency-Key and "
                    + "request returns the first result without calling GitHub or counting against the limit. "
                    + "Limited per app per hour. "
                    + ApiDocs.DEVELOPER
                    + ApiDocs.NO_SESSION,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "202",
                        description = "Requested, or the first result of an earlier request with this key"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "400",
                        description =
                                "Idempotency-Key missing or not 1 to 128 of [A-Za-z0-9_-], an invalid branch, or an unknown field",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = ApiDocs.MEMBERSHIP_403,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "404",
                        description =
                                "APP_NOT_FOUND, REPO_LINK_NOT_FOUND, INSTALLATION_NOT_FOUND, " + ApiDocs.MEMBERSHIP_404,
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description =
                                "IDEMPOTENCY_KEY_REUSE: the key was used for a different request. INSTALLATION_SUSPENDED. "
                                        + "CONCURRENT_MODIFICATION",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "422",
                        description = "BRANCH_NOT_FOUND",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "429",
                        description =
                                "TOO_MANY_REQUESTS: the app's manual build limit is reached; wait meta.retryAfter seconds",
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
    ResponseEntity<ApiResponse<ManualBuildResultDto>> build(
            @PathVariable String orgId,
            @PathVariable UUID appId,
            @Parameter(
                            description = "Names this request; a retry with the same key returns the first result.",
                            required = true,
                            schema = @Schema(pattern = "^[A-Za-z0-9_-]{1,128}$", example = "build-7f3c2a"))
                    @RequestHeader(IDEMPOTENCY_KEY)
                    String idempotencyKey,
            @RequestBody(required = false) ManualBuildRequestDto request) {
        ManualBuildResultDto build = builds.request(orgId, appId, caller(orgId), idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok("Build requested", build));
    }

    private String caller(String orgId) {
        return accessResolver.resolve(orgId).userId();
    }
}
