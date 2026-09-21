package io.pallet.orgteam.invite;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public class InviteNoLongerValidException extends AppException {

    public InviteNoLongerValidException() {
        super(
                HttpStatus.GONE,
                "INVITE_NO_LONGER_VALID",
                "This invitation is no longer valid.",
                "Invite is accepted, revoked, expired or its organization is gone");
    }
}
