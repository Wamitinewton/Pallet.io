package io.pallet.orgteam.invite;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.orgteam.docs.ApiDocs;
import io.pallet.orgteam.security.AccessContext;
import io.pallet.orgteam.security.AccessResolver;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/org-team/orgs/{orgId}/invites")
@Tag(name = "Invites", description = "Invite people to the organization by email.")
class InviteController {

    private final InviteService inviteService;
    private final AccessResolver accessResolver;

    InviteController(InviteService inviteService, AccessResolver accessResolver) {
        this.inviteService = inviteService;
        this.accessResolver = accessResolver;
    }

    @PostMapping
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Invite someone to the organization",
            description = "Emails the invitee a single-use link. The token is never returned. An OWNER may grant "
                    + "ADMIN, DEVELOPER or VIEWER; an ADMIN only DEVELOPER or VIEWER; nobody may grant OWNER. "
                    + ApiDocs.ADMIN + ApiDocs.RETRY_CONFLICTS,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "ALREADY_A_MEMBER, MEMBER_PREVIOUSLY_REMOVED, INVITE_ALREADY_PENDING or "
                                + "QUOTA_EXCEEDED",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ResponseEntity<ApiResponse<InviteDto>> create(
            @PathVariable String orgId, @Valid @RequestBody CreateInviteRequest request) {
        AccessContext caller = accessResolver.resolve(orgId);
        InviteDto invite = inviteService.create(orgId, caller.userId(), request.email(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Invite created", invite));
    }

    @GetMapping
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "List invites",
            description = "An invite past its expiry reads as EXPIRED even before the background sweep marks it. "
                    + ApiDocs.ADMIN)
    ApiResponse<PageResponse<InviteDto>> list(
            @PathVariable String orgId,
            @RequestParam(required = false) InviteStatus status,
            @ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok("Invites retrieved", inviteService.list(orgId, status, pageQuery));
    }

    @PostMapping("/{inviteId}/resend")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Resend a pending invite",
            description = "Issues a fresh link with a new expiry for the same invite. Subject to a cooldown between "
                    + "sends and a maximum send count. " + ApiDocs.ADMIN + ApiDocs.RETRY_CONFLICTS,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "INVITE_NOT_PENDING or QUOTA_EXCEEDED",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<InviteDto> resend(@PathVariable String orgId, @PathVariable UUID inviteId) {
        AccessContext caller = accessResolver.resolve(orgId);
        return ApiResponse.ok("Invite resent", inviteService.resend(orgId, caller.userId(), inviteId));
    }

    @DeleteMapping("/{inviteId}")
    @PreAuthorize("@access.atLeast(#orgId, 'ADMIN')")
    @Operation(
            summary = "Revoke a pending invite",
            description = ApiDocs.REVOKE_NOTE + " " + ApiDocs.ADMIN,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Revoked"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "INVITE_NOT_PENDING",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ResponseEntity<Void> revoke(@PathVariable String orgId, @PathVariable UUID inviteId) {
        AccessContext caller = accessResolver.resolve(orgId);
        inviteService.revoke(orgId, caller.userId(), inviteId);
        return ResponseEntity.noContent().build();
    }
}
