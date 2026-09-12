package io.pallet.identity.invite;

import static org.springframework.http.HttpStatus.CREATED;

import io.pallet.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InviteAcceptController {

    private final InviteAcceptService inviteAcceptService;

    InviteAcceptController(InviteAcceptService inviteAcceptService) {
        this.inviteAcceptService = inviteAcceptService;
    }

    @PostMapping("/invites/{token}/accept")
    ResponseEntity<ApiResponse<Void>> accept(
            @PathVariable String token, @Valid @RequestBody InviteAcceptRequest request) {
        inviteAcceptService.accept(token, request.password());
        return ResponseEntity.status(CREATED).body(ApiResponse.ok("Invite accepted"));
    }
}
