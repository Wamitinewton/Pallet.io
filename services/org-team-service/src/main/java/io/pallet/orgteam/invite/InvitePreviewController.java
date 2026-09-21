package io.pallet.orgteam.invite;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/org-team/invites")
@Tag(name = "Invites", description = "Invite people to the organization by email.")
class InvitePreviewController {

    private final InviteService inviteService;

    InvitePreviewController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @GetMapping("/{token}")
    @SecurityRequirements
    @Operation(
            summary = "Preview an invite",
            description = "Public. Shows what an emailed invite link is for, so the accept page can render before the "
                    + "invitee signs up. The token is verified before anything is read; the invitee's email is masked.",
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "400",
                        description = "INVALID_TOKEN: bad signature, expired or wrong purpose",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "410",
                        description = "INVITE_NO_LONGER_VALID: revoked, accepted or expired",
                        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    ApiResponse<InvitePreviewDto> preview(
            @Parameter(description = "Signed invite token from the invite email") @PathVariable String token) {
        return ApiResponse.ok("Invite retrieved", inviteService.preview(token));
    }
}
