package io.pallet.notification.idempotency;

import io.pallet.common.messaging.EventIdempotencyGuard;
import io.pallet.notification.repository.NotificationRepository;
import java.time.Duration;
import java.util.UUID;

/**
 * The real reservation isn't made here — it's the unique constraint on
 * {@code notifications.source_event_id}, hit by the listener's insert in the consumer checkpoint.
 * {@link #markProcessed} is only a fast pre-check to skip rendering for an obviously-already-handled
 * event; it cannot itself close the race between two consumer instances (or two partitions) seeing
 * the same event, since a plain existence check has a window between the read and the eventual
 * insert. That window is closed by the database: the listener catches the constraint violation on
 * its insert as "another instance already reserved this, no-op." {@link #release} is a no-op
 * because there's no separate reservation state to undo — the row either exists or it doesn't, and
 * a failed processing attempt after a successful insert shouldn't happen if the insert is the last
 * step before channel fan-out begins.
 */
class NotificationEventIdempotencyGuard implements EventIdempotencyGuard {

    private final NotificationRepository notificationRepository;

    NotificationEventIdempotencyGuard(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Override
    public boolean markProcessed(String eventId, Duration retention) {
        return notificationRepository
                .findBySourceEventId(UUID.fromString(eventId))
                .isEmpty();
    }

    @Override
    public void release(String eventId) {}
}
