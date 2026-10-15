package io.pallet.identity.invite;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * The invited email already has an account and the request carried no token. Deliberately not
 * {@code AUTHENTICATION_REQUIRED}: the web BFF reads that code on a token-bearing request as a dead
 * session.
 */
public class InviteSignInRequiredException extends AppException {

    public InviteSignInRequiredException(String jti) {
        super(
                HttpStatus.UNAUTHORIZED,
                "INVITE_SIGN_IN_REQUIRED",
                "Sign in to accept this invite.",
                "Invite " + jti + " is for an existing account and the request is unauthenticated");
    }
}
