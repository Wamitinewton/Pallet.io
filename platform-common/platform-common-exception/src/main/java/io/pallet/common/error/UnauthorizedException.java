package io.pallet.common.error;

import org.springframework.http.HttpStatus;

/** Missing or invalid credentials at the application layer, distinct from Spring Security's own 401. */
public class UnauthorizedException extends AppException {

    public UnauthorizedException(String clientMessage) {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", clientMessage, clientMessage);
    }

    public UnauthorizedException(String clientMessage, String technicalMessage) {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", clientMessage, technicalMessage);
    }
}
