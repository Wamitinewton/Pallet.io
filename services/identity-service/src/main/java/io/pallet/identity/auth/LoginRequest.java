package io.pallet.identity.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
