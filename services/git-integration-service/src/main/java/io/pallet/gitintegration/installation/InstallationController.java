package io.pallet.gitintegration.installation;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.gitintegration.installation.InstallationService.LinkResult;
import io.pallet.gitintegration.installation.dto.InstallSessionResponse;
import io.pallet.gitintegration.installation.dto.InstallationLinkResponse;
import io.pallet.gitintegration.installation.dto.LinkInstallationRequest;
import io.pallet.gitintegration.installation.dto.PickerRepositoryResponse;
import io.pallet.gitintegration.security.AccessResolver;
import jakarta.validation.Valid;
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
    ResponseEntity<ApiResponse<InstallSessionResponse>> startInstall(@PathVariable String orgId) {
        InstallSessionResponse started = installations.startInstall(orgId, caller(orgId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Install session started", started));
    }

    @PostMapping("/installations")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    ResponseEntity<ApiResponse<InstallationLinkResponse>> link(
            @PathVariable String orgId, @Valid @RequestBody LinkInstallationRequest request) {
        LinkResult result = installations.link(orgId, caller(orgId), request);
        return result.created()
                ? ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Installation linked", result.link()))
                : ResponseEntity.ok(ApiResponse.ok("Installation already linked", result.link()));
    }

    @GetMapping("/installations")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    ApiResponse<PageResponse<InstallationLinkResponse>> list(
            @PathVariable String orgId, @ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok(
                "Installations retrieved",
                installations.list(orgId, pageQuery.toPageable(InstallationService.LINK_ORDER)));
    }

    @DeleteMapping("/installations/{installationId}")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    ResponseEntity<Void> unlink(@PathVariable String orgId, @PathVariable long installationId) {
        installations.unlink(orgId, installationId, caller(orgId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/installations/{installationId}/repositories")
    @PreAuthorize("@access.atLeast(#orgId, 'DEVELOPER')")
    ApiResponse<PageResponse<PickerRepositoryResponse>> repositories(
            @PathVariable String orgId,
            @PathVariable long installationId,
            @RequestParam(required = false) String q,
            @ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok(
                "Repositories retrieved",
                picker.list(orgId, caller(orgId), installationId, q, pageQuery.toPageable(Sort.unsorted())));
    }

    private String caller(String orgId) {
        return accessResolver.resolve(orgId).userId();
    }
}
