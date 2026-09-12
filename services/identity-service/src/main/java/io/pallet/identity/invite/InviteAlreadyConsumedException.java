package io.pallet.identity.invite;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * The signed invite token behind this request was already used to create an account. Replaying
 * accept isn't idempotent the way sign-up is — a second attempt would try to create a second
 * Keycloak user for an email that, by definition, already has one.
 */
public class InviteAlreadyConsumedException extends AppException {

    public InviteAlreadyConsumedException(String jti) {
        super(
                HttpStatus.CONFLICT,
                "INVITE_ALREADY_CONSUMED",
                "This invite has already been accepted.",
                "Invite token " + jti + " already consumed");
    }
}
