package io.pallet.identity.account;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * The requested session id doesn't belong to the caller's own Keycloak sessions — either it
 * never existed or it belongs to a different user. Both cases look identical from here, since
 * ownership is checked by scoping the lookup to the caller's own session list rather than
 * querying Keycloak by session id directly.
 */
public class SessionNotFoundException extends AppException {

    public SessionNotFoundException(String sessionId) {
        super(
                HttpStatus.NOT_FOUND,
                "SESSION_NOT_FOUND",
                "Session not found.",
                "No active session " + sessionId + " for this user");
    }
}
