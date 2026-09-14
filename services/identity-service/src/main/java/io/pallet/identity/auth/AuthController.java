package io.pallet.identity.auth;

import static org.springframework.http.HttpStatus.ACCEPTED;

import io.pallet.common.api.ApiResponse;
import io.pallet.identity.account.PasswordResetService;
import io.pallet.identity.verification.EmailVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/identity")
@Tag(name = "Authentication")
class AuthController {

    private final EmailVerificationService emailVerificationService;
    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    AuthController(
            EmailVerificationService emailVerificationService,
            AuthService authService,
            PasswordResetService passwordResetService) {
        this.emailVerificationService = emailVerificationService;
        this.authService = authService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Exchange credentials for an access/refresh token pair")
    @SecurityRequirements
    ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse tokens = authService.login(request.email(), request.password());
        return ResponseEntity.ok(ApiResponse.ok("Logged in", tokens));
    }

    @PostMapping("/auth/refresh")
    @Operation(summary = "Exchange a refresh token for a new access/refresh token pair")
    @SecurityRequirements
    ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Token refreshed", authService.refresh(request.refreshToken())));
    }

    @PostMapping("/auth/logout")
    @Operation(summary = "Revoke the caller's current refresh token")
    ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok("Logged out"));
    }

    @PostMapping("/auth/email/resend-verification")
    @Operation(summary = "Resend an email-verification code, invalidating any existing one")
    @SecurityRequirements
    ResponseEntity<ApiResponse<Void>> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        emailVerificationService.resend(request.email());
        return ResponseEntity.status(ACCEPTED).body(ApiResponse.ok("Verification code sent if the account exists"));
    }

    @PostMapping("/auth/email/verify")
    @Operation(summary = "Confirm an email-verification code")
    @SecurityRequirements
    ResponseEntity<ApiResponse<Void>> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        emailVerificationService.verify(request.email(), request.code());
        return ResponseEntity.ok(ApiResponse.ok("Email verified"));
    }

    @PostMapping("/auth/password/forgot")
    @Operation(summary = "Request a password-reset token by email")
    ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.status(ACCEPTED)
                .body(ApiResponse.ok("Password reset instructions sent if the account exists"));
    }

    @PostMapping("/auth/password/reset")
    @Operation(summary = "Complete a password reset with a single-use token")
    ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.completeReset(request.token(), request.newPassword());
        return ResponseEntity.ok(ApiResponse.ok("Password reset"));
    }
}
