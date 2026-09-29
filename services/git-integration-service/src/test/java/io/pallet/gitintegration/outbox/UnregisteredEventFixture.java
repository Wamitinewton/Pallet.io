package io.pallet.gitintegration.outbox;

import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.PlatformEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Constructs events both ways OutboxEventTypesTest must detect; never loaded by the application. */
final class UnregisteredEventFixture {

    private UnregisteredEventFixture() {}

    static List<PlatformEvent> events(GitPushReceived received) {
        return List.of(
                OrgDeleted.of("org-a", "user-1"),
                new AppDeleted(UUID.randomUUID(), AppDeleted.TYPE, "org-a", Instant.EPOCH, "app", "slug", "user-1"),
                received);
    }
}
