package io.pallet.identity.account;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(@NotBlank String displayName) {}
