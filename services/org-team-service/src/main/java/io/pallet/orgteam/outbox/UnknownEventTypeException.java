package io.pallet.orgteam.outbox;

class UnknownEventTypeException extends RuntimeException {

    UnknownEventTypeException(String eventType) {
        super("No event class registered for type " + eventType);
    }
}
