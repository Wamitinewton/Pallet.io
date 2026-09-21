package io.pallet.orgteam.org;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.orgteam.docs.ApiDocs;
import io.pallet.orgteam.security.AccessContext;
import io.pallet.orgteam.security.AccessResolver;
import io.pallet.orgteam.security.RecentAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/org-team/orgs")
@Tag(name = "Organizations", description = "Read, rename and delete the caller's organization.")
class OrgController {

    private final OrgService orgService;
    private final OrgDeletionService deletionService;
    private final AccessResolver accessResolver;
    private final RecentAuthentication recentAuthentication;

    OrgController(
            OrgService orgService,
            OrgDeletionService deletionService,
            AccessResolver accessResolver,
            RecentAuthentication recentAuthentication) {
        this.orgService = orgService;
        this.deletionService = deletionService;
        this.accessResolver = accessResolver;
        this.recentAuthentication = recentAuthentication;
    }

    @GetMapping("/{orgId}")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(
            summary = "Get an organization",
            description = "Returns the organization with live member, team and app counts. " + ApiDocs.ANY_MEMBER
                    + " An organization other than the caller's own is reported as 404 ORG_NOT_FOUND.")
    ApiResponse<OrgDto> get(@PathVariable String orgId) {
        return ApiResponse.ok("Organization retrieved", orgService.get(orgId));
    }

    @PatchMapping("/{orgId}")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Rename an organization",
            description = "Changes the display name only; the slug and owner never change here. " + ApiDocs.ADMIN)
    ApiResponse<OrgDto> update(@PathVariable String orgId, @Valid @RequestBody UpdateOrgRequest request) {
        String actor = accessResolver.resolve(orgId).userId();
        return ApiResponse.ok("Organization updated", orgService.rename(orgId, actor, request.name()));
    }

    @DeleteMapping("/{orgId}")
    @PreAuthorize("@access.isOwner(#orgId)")
    @Operation(
            summary = "Delete an organization",
            description = "Soft-deletes the organization: every member is removed, pending invites are revoked and "
                    + "apps are deleted. " + ApiDocs.OWNER + ApiDocs.RECENT_AUTH
                    + " The X-Confirm-Slug header must equal the organization's slug.",
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = "INSUFFICIENT_ROLE, NOT_A_MEMBER or REAUTHENTICATION_REQUIRED",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "400",
                        description = "CONFIRMATION_MISMATCH: X-Confirm-Slug is missing or wrong",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ResponseEntity<Void> delete(
            @PathVariable String orgId,
            @Parameter(
                            description = "The organization's slug, repeated to confirm the deletion",
                            example = "acme-corp",
                            required = true)
                    @RequestHeader(name = "X-Confirm-Slug", required = false)
                    String confirmedSlug) {
        AccessContext caller = accessResolver.resolve(orgId);
        recentAuthentication.require(caller);
        deletionService.delete(orgId, caller.userId(), confirmedSlug);
        return ResponseEntity.noContent().build();
    }
}
