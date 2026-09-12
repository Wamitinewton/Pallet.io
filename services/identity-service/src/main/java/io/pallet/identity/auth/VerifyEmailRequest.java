package io.pallet.identity.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(
        @NotBlank @Email String email, @NotBlank String code) {}
