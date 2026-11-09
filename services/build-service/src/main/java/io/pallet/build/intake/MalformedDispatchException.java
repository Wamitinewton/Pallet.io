package io.pallet.build.intake;

import io.pallet.common.inbox.MalformedEventException;

/** A {@code build.dispatched} that no retry can make valid; dead-lettered on its first delivery. */
public class MalformedDispatchException extends MalformedEventException {

    MalformedDispatchException(String message) {
        super(message);
    }
}
