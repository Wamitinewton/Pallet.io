package io.pallet.common.error;

import org.springframework.http.HttpStatus;

/**
 * The same idempotency key was replayed with a different payload.
 */
public class IdempotencyKeyReuseException extends AppException {

    public IdempotencyKeyReuseException(String clientMessage) {
        super(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSE", clientMessage, clientMessage);
    }
}
