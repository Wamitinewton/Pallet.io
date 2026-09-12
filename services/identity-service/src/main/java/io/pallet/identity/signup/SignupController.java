package io.pallet.identity.signup;

import static org.springframework.http.HttpStatus.CREATED;

import io.pallet.common.api.ApiResponse;
import io.pallet.identity.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SignupController {

    private final IdempotencyService idempotencyService;
    private final SignupService signupService;

    SignupController(IdempotencyService idempotencyService, SignupService signupService) {
        this.idempotencyService = idempotencyService;
        this.signupService = signupService;
    }

    @PostMapping("/signup")
    ResponseEntity<ApiResponse<SignupResponse>> signup(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody SignupRequest request) {
        SignupResponse response = idempotencyService.execute(
                idempotencyKey, request, () -> signupService.provision(request), SignupResponse.class);
        return ResponseEntity.status(CREATED).body(ApiResponse.ok("Organization provisioned", response));
    }
}
