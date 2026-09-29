package io.pallet.gitintegration.repolink;

import io.pallet.common.api.ApiResponse;
import io.pallet.gitintegration.repolink.dto.ManualBuildRequestDto;
import io.pallet.gitintegration.repolink.dto.ManualBuildResultDto;
import io.pallet.gitintegration.repolink.dto.PatchRepoLinkRequest;
import io.pallet.gitintegration.repolink.dto.PutRepoLinkRequest;
import io.pallet.gitintegration.repolink.dto.RepoLinkDto;
import io.pallet.gitintegration.security.AccessResolver;
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
    ResponseEntity<ApiResponse<RepoLinkDto>> link(
            @PathVariable String orgId, @PathVariable UUID appId, @Valid @RequestBody PutRepoLinkRequest request) {
        RepoLinkDto link = links.link(orgId, appId, caller(orgId), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Repository linked", link));
    }

    @GetMapping
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    ApiResponse<RepoLinkDto> get(@PathVariable String orgId, @PathVariable UUID appId) {
        return ApiResponse.ok("Repository link retrieved", links.get(orgId, appId));
    }

    @PatchMapping
    @PreAuthorize("@access.atLeast(#orgId, 'DEVELOPER')")
    ApiResponse<RepoLinkDto> update(
            @PathVariable String orgId, @PathVariable UUID appId, @Valid @RequestBody PatchRepoLinkRequest request) {
        return ApiResponse.ok("Repository link updated", links.update(orgId, appId, caller(orgId), request));
    }

    @PostMapping("/verification")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    ApiResponse<RepoLinkDto> takeOverVerification(@PathVariable String orgId, @PathVariable UUID appId) {
        return ApiResponse.ok("Repository access verified", links.takeOverVerification(orgId, appId, caller(orgId)));
    }

    @DeleteMapping
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    ResponseEntity<Void> disconnect(@PathVariable String orgId, @PathVariable UUID appId) {
        links.disconnect(orgId, appId, caller(orgId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/builds")
    @PreAuthorize("@access.atLeast(#orgId, 'DEVELOPER')")
    ResponseEntity<ApiResponse<ManualBuildResultDto>> build(
            @PathVariable String orgId,
            @PathVariable UUID appId,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody(required = false) ManualBuildRequestDto request) {
        ManualBuildResultDto build = builds.request(orgId, appId, caller(orgId), idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok("Build requested", build));
    }

    private String caller(String orgId) {
        return accessResolver.resolve(orgId).userId();
    }
}
