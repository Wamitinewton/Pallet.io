package io.pallet.common.inbox;

public class MalformedEventException extends NonRetryableEventException {

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
