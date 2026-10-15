package io.pallet.identity.invite;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

/** The signed-in account isn't the one that owns the invited email. The invite stays usable. */
public class InviteEmailMismatchException extends AppException {

    public InviteEmailMismatchException(String jti, String callerUserId) {
        super(
                HttpStatus.FORBIDDEN,
                "INVITE_EMAIL_MISMATCH",
                "This invite was sent to a different email address. Sign in with that account to accept it.",
                "Caller " + callerUserId + " does not own the email invite " + jti + " was sent to");
    }
}
