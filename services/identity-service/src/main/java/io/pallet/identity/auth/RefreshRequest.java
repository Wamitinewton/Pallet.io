package io.pallet.identity.auth;

import jakarta.validation.constraints.NotBlank;

record RefreshRequest(@NotBlank String refreshToken) {}
