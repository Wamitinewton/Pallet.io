package io.pallet.build.intake;

import io.pallet.common.inbox.NonRetryableEventException;

/**
 * A dispatch whose {@code buildId} is already recorded for another org, app or commit. The queue derives the id from
 * the push, so this is a bug or a forgery; applying it could run one tenant's build as another's, so it is
 * dead-lettered.
 */
public class DispatchConflictException extends NonRetryableEventException {

    DispatchConflictException(String field) {
        super("BuildDispatched." + field + " differs from the build already recorded under its buildId");
    }
}
