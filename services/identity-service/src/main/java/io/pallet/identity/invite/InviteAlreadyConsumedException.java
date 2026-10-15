package io.pallet.identity.invite;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * The signed invite token behind this request was already accepted. Replaying accept isn't
 * idempotent the way sign-up is: a second attempt would publish a second {@code OrgInviteAccepted}.
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
