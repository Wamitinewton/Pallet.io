package io.pallet.orgteam.member;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.orgteam.docs.ApiDocs;
import io.pallet.orgteam.security.AccessContext;
import io.pallet.orgteam.security.AccessResolver;
import io.pallet.orgteam.security.RecentAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/org-team/orgs/{orgId}/members")
@Tag(name = "Members", description = "Organization membership: roles, removal and ownership transfer.")
class MemberController {

    private final MemberService memberService;
    private final AccessResolver accessResolver;
    private final RecentAuthentication recentAuthentication;

    MemberController(
            MemberService memberService, AccessResolver accessResolver, RecentAuthentication recentAuthentication) {
        this.memberService = memberService;
        this.accessResolver = accessResolver;
        this.recentAuthentication = recentAuthentication;
    }

    @GetMapping
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(
            summary = "List members",
            description = "Active members by default. Filtering on status REMOVED requires ADMIN or above. "
                    + "Sortable by displayName, joinedAt and role; any other sort field is a 400 INVALID_SORT. "
                    + ApiDocs.ANY_MEMBER)
    ApiResponse<PageResponse<MemberDto>> list(
            @PathVariable String orgId,
            @Valid @ModelAttribute MemberFilter filter,
            @ModelAttribute PageQuery pageQuery) {
        AccessContext caller = accessResolver.resolve(orgId);
        return ApiResponse.ok("Members retrieved", memberService.list(orgId, caller.role(), filter, pageQuery));
    }

    @GetMapping("/me")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(
            summary = "Get the caller's own membership",
            description = "Returns the caller's current role, so a client needs no token-claim parsing. "
                    + ApiDocs.ANY_MEMBER)
    ApiResponse<MemberDto> me(@PathVariable String orgId) {
        AccessContext caller = accessResolver.resolve(orgId);
        return ApiResponse.ok("Member retrieved", memberService.get(orgId, caller.userId()));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(summary = "Get a member", description = ApiDocs.ANY_MEMBER)
    ApiResponse<MemberDto> get(@PathVariable String orgId, @PathVariable String userId) {
        return ApiResponse.ok("Member retrieved", memberService.get(orgId, userId));
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("@access.isOwner(#orgId)")
    @Operation(
            summary = "Change a member's role",
            description = "Sets the role to ADMIN, DEVELOPER or VIEWER. The owner cannot be targeted and OWNER cannot "
                    + "be assigned here; use transfer-ownership. Setting the current role is a 200 with no change. "
                    + ApiDocs.OWNER,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "INVALID_ROLE_TRANSITION, LAST_OWNER or CONCURRENT_MODIFICATION",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<MemberDto> changeRole(
            @PathVariable String orgId, @PathVariable String userId, @Valid @RequestBody ChangeRoleRequest request) {
        AccessContext caller = accessResolver.resolve(orgId);
        return ApiResponse.ok(
                "Member role updated", memberService.changeRole(orgId, caller.userId(), userId, request.role()));
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("@access.atLeast(#orgId, 'VIEWER')")
    @Operation(
            summary = "Remove a member or leave the organization",
            description = "The owner may remove anyone but themselves; an ADMIN may remove DEVELOPER and VIEWER "
                    + "members; any member may remove themselves. The owner cannot be removed or leave: transfer "
                    + "ownership first. Removing an already-removed member is a 404. "
                    + ApiDocs.ANY_MEMBER + " Further limits depend on the target's role.",
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Removed"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "LAST_OWNER or CONCURRENT_MODIFICATION",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ResponseEntity<Void> remove(@PathVariable String orgId, @PathVariable String userId) {
        AccessContext caller = accessResolver.resolve(orgId);
        memberService.remove(orgId, caller.userId(), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/transfer-ownership")
    @PreAuthorize("@access.isOwner(#orgId)")
    @Operation(
            summary = "Transfer ownership to another member",
            description = "The target becomes OWNER and the caller becomes ADMIN in one transaction. The target must "
                    + "be an active member. " + ApiDocs.OWNER + ApiDocs.RECENT_AUTH
                    + " A retry after success is rejected because the caller is no longer the owner; it does not "
                    + "transfer twice.",
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "403",
                        description = "INSUFFICIENT_ROLE, NOT_A_MEMBER or REAUTHENTICATION_REQUIRED",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "409",
                        description = "INVALID_ROLE_TRANSITION or CONCURRENT_MODIFICATION",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<MemberDto> transferOwnership(@PathVariable String orgId, @PathVariable String userId) {
        AccessContext caller = accessResolver.resolve(orgId);
        recentAuthentication.require(caller);
        return ApiResponse.ok("Ownership transferred", memberService.transferOwnership(orgId, caller.userId(), userId));
    }
}
