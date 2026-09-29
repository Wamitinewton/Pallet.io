package io.pallet.common.outbox;

public class UnknownEventTypeException extends RuntimeException {

    UnknownEventTypeException(String eventType) {
        super("No event class registered for type " + eventType);
    }
}
