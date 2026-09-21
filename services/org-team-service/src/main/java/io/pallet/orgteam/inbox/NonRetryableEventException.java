package io.pallet.orgteam.inbox;

public abstract class NonRetryableEventException extends RuntimeException {

    protected NonRetryableEventException(String message) {
        super(message);
    }

    protected NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
