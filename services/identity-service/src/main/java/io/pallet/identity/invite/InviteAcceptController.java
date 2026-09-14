package io.pallet.identity.invite;

import static org.springframework.http.HttpStatus.CREATED;

import io.pallet.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/identity")
@Tag(name = "Invites")
class InviteAcceptController {

    private final InviteAcceptService inviteAcceptService;

    InviteAcceptController(InviteAcceptService inviteAcceptService) {
        this.inviteAcceptService = inviteAcceptService;
    }

    @PostMapping("/invites/{token}/accept")
    @Operation(summary = "Accept an org invite and create an account")
    @SecurityRequirements
    ResponseEntity<ApiResponse<Void>> accept(
            @Parameter(description = "Signed invite token from the invite email") @PathVariable String token,
            @Valid @RequestBody InviteAcceptRequest request) {
        inviteAcceptService.accept(token, request.password());
        return ResponseEntity.status(CREATED).body(ApiResponse.ok("Invite accepted"));
    }
}
