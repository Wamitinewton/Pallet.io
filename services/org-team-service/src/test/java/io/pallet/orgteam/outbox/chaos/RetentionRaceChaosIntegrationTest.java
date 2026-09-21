package io.pallet.orgteam.outbox.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.orgteam.retention.RetentionSweeps;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The retention sweep deletes published rows while the relay publishes and writers append. */
class RetentionRaceChaosIntegrationTest extends OutboxChaosSupport {

    private static final int EXPIRED_ROWS = 60;
    private static final int RECENT_ROWS = 5;
    private static final int PARKED_ROWS = 3;

    @Autowired
    private RetentionSweeps sweeps;

    @Test
    void aSweepRunningAgainstTheLiveRelayDeletesOnlyExpiredPublishedRows() throws Exception {
        long seed = ChaosSeed.next("aSweepRunningAgainstTheLiveRelayDeletesOnlyExpiredPublishedRows");
        String archive = newOrg();
        String stuck = newOrg();
        List<String> live = newOrgs(3);
        seed(archive, "PUBLISHED", "now() - interval '8 days'", EXPIRED_ROWS);
        List<UUID> recent = seed(archive, "PUBLISHED", "now() - interval '1 hour'", RECENT_ROWS);
        List<UUID> parked = seed(stuck, "PARKED", "NULL", PARKED_ROWS);
        AtomicBoolean sweeping = new AtomicBoolean(true);
        AtomicReference<Throwable> sweepFailure = new AtomicReference<>();
        Thread sweeper = Thread.ofPlatform().start(() -> {
            try {
                while (sweeping.get()) {
                    sweeps.sweepPublishedOutbox();
                    Pauses.sleep(Duration.ofMillis(20));
                }
            } catch (RuntimeException | Error e) {
                sweepFailure.set(e);
            }
        });

        try (LoadGenerator load =
                new LoadGenerator(transaction, writer, ledger, live, 3, 0.1, Duration.ofMillis(15), seed)) {
            Pauses.sleep(Duration.ofSeconds(4));
        } finally {
            sweeping.set(false);
            sweeper.join();
        }

        assertThat(sweepFailure.get())
                .as("the sweep must not fail while the relay runs")
                .isNull();
        awaitDrained(live);
        sweeps.sweepPublishedOutbox();
        assertThat(rowsFor(archive))
                .as("expired rows are gone, recent rows stay")
                .isEqualTo(RECENT_ROWS);
        assertThat(survivors(recent)).isEqualTo(RECENT_ROWS);
        assertThat(survivors(parked)).as("parked rows are never swept").isEqualTo(PARKED_ROWS);
        assertThat(ledger.totalCommitted()).as("the load actually ran").isGreaterThan(30);
        OutboxInvariants.verifyAll(ledger, deliveries(), jdbc, consumer, 0);
    }

    private List<UUID> seed(String orgId, String status, String publishedAt, int count) {
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID eventId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO org_team.outbox_events (event_id, org_id, event_type, payload, status, attempts, published_at) "
                            + "VALUES (?, ?, 'org.member.added', '{}'::jsonb, ?, ?, " + publishedAt + ")",
                    eventId,
                    orgId,
                    status,
                    "PARKED".equals(status) ? properties.outbox().maxAttempts() : 0);
            ids.add(eventId);
        }
        return ids;
    }

    private int rowsFor(String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ?", Integer.class, orgId);
    }

    private int survivors(List<UUID> eventIds) {
        return eventIds.stream()
                .mapToInt(id -> jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.outbox_events WHERE event_id = ?", Integer.class, id))
                .sum();
    }
}
