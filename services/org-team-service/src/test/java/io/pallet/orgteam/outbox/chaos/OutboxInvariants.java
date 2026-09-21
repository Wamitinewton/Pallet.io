package io.pallet.orgteam.outbox.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.orgteam.outbox.TopicProbe.Received;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The guarantees the outbox pattern makes, as assertions against a {@link DeliveryLedger}. Every
 * chaos test ends by checking these, whatever fault it injected.
 */
final class OutboxInvariants {

    private OutboxInvariants() {}

    /** Every committed event reached the broker at least once. */
    static void noLoss(DeliveryLedger ledger, Deliveries deliveries) {
        for (String orgId : ledger.orgs()) {
            assertThat(deliveries.idsForOrg(orgId))
                    .as("committed events delivered for %s", orgId)
                    .containsAll(ledger.committedFor(orgId));
        }
    }

    /** An event whose transaction rolled back never reached the broker. */
    static void noPhantoms(DeliveryLedger ledger, Deliveries deliveries) {
        for (String orgId : ledger.orgs()) {
            Set<UUID> phantoms = new HashSet<>(deliveries.idsForOrg(orgId));
            phantoms.retainAll(ledger.rolledBackFor(orgId));
            assertThat(phantoms)
                    .as("rolled-back events delivered for %s", orgId)
                    .isEmpty();
        }
    }

    /** Nothing but the ledger's events appeared for its orgs. */
    static void noStrangers(DeliveryLedger ledger, Deliveries deliveries) {
        for (String orgId : ledger.orgs()) {
            assertThat(deliveries.firstOccurrenceOrder(orgId))
                    .as("distinct events delivered for %s", orgId)
                    .containsExactlyInAnyOrderElementsOf(ledger.committedFor(orgId));
        }
    }

    /** Per org, events first appear in the order they were committed. */
    static void orderPreserved(DeliveryLedger ledger, Deliveries deliveries) {
        for (String orgId : ledger.orgs()) {
            assertThat(deliveries.firstOccurrenceOrder(orgId))
                    .as("first-delivery order for %s", orgId)
                    .containsExactlyElementsOf(ledger.committedFor(orgId));
        }
    }

    /** Redelivery is allowed, but a redelivered event must be the same event, byte for byte. */
    static void duplicatesAreIdentical(Deliveries deliveries) {
        Map<UUID, List<Received>> byId = deliveries.all().stream().collect(Collectors.groupingBy(Received::eventId));
        byId.forEach((eventId, copies) -> {
            Received first = copies.getFirst();
            for (Received copy : copies) {
                assertThat(copy.body()).as("body of redelivered %s", eventId).isEqualTo(first.body());
                assertThat(copy.key()).as("key of redelivered %s", eventId).isEqualTo(first.key());
            }
        });
    }

    static void duplicatesAtMost(Deliveries deliveries, int limit) {
        assertThat(deliveries.duplicateCount())
                .as("redeliveries, bounded by the number of injected interruptions")
                .isLessThanOrEqualTo(limit);
    }

    /** Postgres agrees with Kafka: nothing left open, and one published row per committed event. */
    static void outboxDrained(DeliveryLedger ledger, JdbcTemplate jdbc) {
        for (String orgId : ledger.orgs()) {
            Integer open = jdbc.queryForObject(
                    "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ? AND status <> 'PUBLISHED'",
                    Integer.class,
                    orgId);
            Integer published = jdbc.queryForObject(
                    "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ? AND status = 'PUBLISHED'",
                    Integer.class,
                    orgId);
            assertThat(open).as("open outbox rows for %s", orgId).isZero();
            assertThat(published)
                    .as("published outbox rows for %s", orgId)
                    .isEqualTo(ledger.committedFor(orgId).size());
        }
    }

    /** A consumer behind the inbox guard applies each committed event exactly once, however often it arrives. */
    static void consumerEffectsExactlyOnce(
            DeliveryLedger ledger, Deliveries deliveries, IdempotentConsumerModel consumer) {
        assertThat(consumer.apply(deliveries.all()))
                .as("effects applied by an idempotent consumer")
                .isEqualTo(ledger.totalCommitted());
    }

    /** The full contract, for a run that ends healed and drained. */
    static void verifyAll(
            DeliveryLedger ledger,
            Deliveries deliveries,
            JdbcTemplate jdbc,
            IdempotentConsumerModel consumer,
            int maxDuplicates) {
        noLoss(ledger, deliveries);
        noPhantoms(ledger, deliveries);
        noStrangers(ledger, deliveries);
        orderPreserved(ledger, deliveries);
        duplicatesAreIdentical(deliveries);
        duplicatesAtMost(deliveries, maxDuplicates);
        outboxDrained(ledger, jdbc);
        consumerEffectsExactlyOnce(ledger, deliveries, consumer);
    }
}
