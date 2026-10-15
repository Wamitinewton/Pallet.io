package io.pallet.identity.invite;

import jakarta.validation.constraints.Size;

/** {@code password} is required only when the invite creates the account; an existing account ignores it. */
public record InviteAcceptRequest(
        @Size(min = 12, message = "must be at least 12 characters") String password) {}
