package io.pallet.common.error;

import org.springframework.http.HttpStatus;

/** State conflict — a concurrent duplicate in flight, a resource already in the target state. */
public class ConflictException extends AppException {

    public ConflictException(String clientMessage) {
        super(HttpStatus.CONFLICT, "CONFLICT", clientMessage, clientMessage);
    }

    public ConflictException(String clientMessage, String technicalMessage) {
        super(HttpStatus.CONFLICT, "CONFLICT", clientMessage, technicalMessage);
    }
}
