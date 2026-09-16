package io.pallet.identity.signup;

import static org.springframework.http.HttpStatus.CREATED;

import io.pallet.common.api.ApiResponse;
import io.pallet.identity.idempotency.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/identity")
@Tag(name = "Sign-up")
class SignupController {

    private final IdempotencyService idempotencyService;
    private final SignupService signupService;

    SignupController(IdempotencyService idempotencyService, SignupService signupService) {
        this.idempotencyService = idempotencyService;
        this.signupService = signupService;
    }

    @PostMapping("/signup")
    @Operation(summary = "Bootstrap a new organization and its owner account")
    @SecurityRequirements
    ResponseEntity<ApiResponse<SignupResponse>> signup(
            @Parameter(description = "Client-generated key making a retried sign-up safe")
                    @RequestHeader("Idempotency-Key")
                    String idempotencyKey,
            @Valid @RequestBody SignupRequest request) {
        SignupResponse response = idempotencyService.execute(
                idempotencyKey, request, () -> signupService.provision(request), SignupResponse.class);
        return ResponseEntity.status(CREATED).body(ApiResponse.ok("Organization provisioned", response));
    }

    @GetMapping("/signup/slugs/{slug}/availability")
    @Operation(summary = "Check whether an organization slug is available before signing up")
    @SecurityRequirements
    ResponseEntity<ApiResponse<SlugAvailabilityResponse>> checkSlugAvailability(
            @Parameter(description = "Candidate organization slug")
                    @PathVariable
                    @Pattern(regexp = SignupRequest.SLUG_PATTERN, message = SignupRequest.SLUG_PATTERN_MESSAGE) String slug) {
        boolean available = signupService.isSlugAvailable(slug);
        return ResponseEntity.ok(
                ApiResponse.ok("Slug availability checked", new SlugAvailabilityResponse(slug, available)));
    }
}
