package io.pallet.common.error;

import org.springframework.http.HttpStatus;

/** Authenticated but not allowed — e.g. the wrong {@code org_id} for the resource. */
public class ForbiddenException extends AppException {

    public ForbiddenException(String clientMessage) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", clientMessage, clientMessage);
    }

    public ForbiddenException(String clientMessage, String technicalMessage) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", clientMessage, technicalMessage);
    }
}
