package io.pallet.identity.auth;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * A Keycloak password or refresh-token grant failed for an ordinary reason — wrong password,
 * unknown user, or an expired/revoked refresh token. Deliberately distinct from
 * {@link EmailNotVerifiedException}: both surface as Keycloak's {@code invalid_grant}, but only
 * this one means the credential itself is wrong.
 */
public class InvalidCredentialsException extends AppException {

    public InvalidCredentialsException(String technicalMessage) {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials.", technicalMessage);
    }
}
