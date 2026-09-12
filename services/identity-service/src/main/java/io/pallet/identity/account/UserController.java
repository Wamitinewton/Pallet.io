package io.pallet.identity.account;

import io.pallet.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
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
}
