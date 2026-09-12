package io.pallet.identity.auth;

import jakarta.validation.constraints.NotBlank;

record LogoutRequest(@NotBlank String refreshToken) {}
