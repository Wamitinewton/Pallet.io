package io.pallet.buildqueue.intake;

import io.pallet.common.inbox.MalformedEventException;

/** A {@code git.push.received} that no retry can make valid; dead-lettered on its first delivery. */
public class MalformedPushException extends MalformedEventException {

    MalformedPushException(String message) {
        super(message);
    }
}
