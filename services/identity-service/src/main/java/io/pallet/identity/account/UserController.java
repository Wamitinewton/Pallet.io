package io.pallet.identity.account;

import io.pallet.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/identity")
class UserController {

    private final UserService userService;

    UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/users/me")
    ResponseEntity<ApiResponse<UserProfileResponse>> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok("Profile retrieved", userService.getProfile(jwt)));
    }

    @PatchMapping("/users/me")
    ResponseEntity<ApiResponse<Void>> updateProfile(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        userService.updateProfile(jwt, request.displayName());
        return ResponseEntity.ok(ApiResponse.ok("Profile updated"));
    }

    @PostMapping("/users/me/password")
    ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(jwt, request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(ApiResponse.ok("Password changed"));
    }

    @GetMapping("/users/me/sessions")
    ResponseEntity<ApiResponse<List<SessionResponse>>> listSessions(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok("Sessions retrieved", userService.listSessions(jwt)));
    }

    @DeleteMapping("/users/me/sessions/{sessionId}")
    ResponseEntity<ApiResponse<Void>> revokeSession(@AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId) {
        userService.revokeSession(jwt, sessionId);
        return ResponseEntity.ok(ApiResponse.ok("Session revoked"));
    }

    @DeleteMapping("/users/me/sessions")
    ResponseEntity<ApiResponse<Void>> revokeOtherSessions(@AuthenticationPrincipal Jwt jwt) {
        userService.revokeOtherSessions(jwt);
        return ResponseEntity.ok(ApiResponse.ok("Other sessions revoked"));
    }
}
