package io.pallet.identity.invite;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteAcceptRequest(
        @NotBlank @Size(min = 12, message = "must be at least 12 characters") String password) {}
