package io.pallet.common.inbox;

/** A listener failure that no retry can fix; the listener error handler dead-letters it on the first pass. */
public abstract class NonRetryableEventException extends RuntimeException {

    protected NonRetryableEventException(String message) {
        super(message);
    }

    protected NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
