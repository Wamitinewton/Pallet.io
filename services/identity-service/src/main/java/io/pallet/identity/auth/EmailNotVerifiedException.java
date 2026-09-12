package io.pallet.identity.auth;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * Keycloak refused a password grant because the account's {@code VERIFY_EMAIL} required action is
 * still pending — the credential was correct, the account just isn't usable yet.
 */
public class EmailNotVerifiedException extends AppException {

    public EmailNotVerifiedException() {
        super(
                HttpStatus.FORBIDDEN,
                "EMAIL_NOT_VERIFIED",
                "Verify your email before signing in.",
                "Keycloak rejected the grant: account not fully set up (pending VERIFY_EMAIL)");
    }
}
