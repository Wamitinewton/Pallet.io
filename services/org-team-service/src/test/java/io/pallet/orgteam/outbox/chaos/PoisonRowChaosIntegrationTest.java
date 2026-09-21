package io.pallet.orgteam.outbox.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.outbox.TopicProbe;
import io.pallet.orgteam.outbox.TopicProbe.Received;
import io.pallet.orgteam.outbox.chaos.PostgresFaults.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** A row the broker or the codec can never accept: it must park, isolate its org, and be repairable. */
class PoisonRowChaosIntegrationTest extends OutboxChaosSupport {

    private static final int OVERSIZE_CHARACTERS = 2_000_000;

    enum Poison {
        UNKNOWN_EVENT_TYPE,
        MALFORMED_PAYLOAD,
        MISSING_PAYLOAD,
        OVERSIZE_RECORD
    }

    @ParameterizedTest
    @EnumSource(Poison.class)
    void aPoisonRowParksAfterMaxAttemptsHoldsOnlyItsOwnOrgAndUnblocksWhenRepaired(Poison poison) {
        String poisoned = newOrg();
        String healthy = newOrg();
        UUID poisonId = insertPoison(poison, poisoned);
        double parkedBefore = gaugeAfterRefresh(MetricsCatalog.OUTBOX_PARKED);
        double rowFailuresBefore = rowFailures();
        OrgMemberAdded behindPoison = memberAdded(poisoned);
        OrgMemberAdded unrelated = memberAdded(healthy);
        commit(behindPoison);
        commit(unrelated);

        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            assertThat(statusOf(unrelated.eventId())).isEqualTo("PUBLISHED");
            assertThat(statusOf(poisonId)).isEqualTo("PARKED");
        });
        outboxMetrics.refresh();

        assertThat(attemptsOf(poisonId)).isEqualTo(properties.outbox().maxAttempts());
        assertThat(rowFailures())
                .isGreaterThanOrEqualTo(rowFailuresBefore + properties.outbox().maxAttempts());
        assertThat(gauge(MetricsCatalog.OUTBOX_PARKED)).isEqualTo(parkedBefore + 1);
        assertThat(statusOf(behindPoison.eventId()))
                .as("the parked row blocks its own org")
                .isEqualTo("PENDING");
        assertThat(lastError(poisonId))
                .as("errors record types only, never payload fragments")
                .isNotBlank()
                .hasSizeLessThanOrEqualTo(500)
                .doesNotContain("xxxx", "member@example.com");

        jdbc.update("DELETE FROM org_team.outbox_events WHERE event_id = ?", poisonId);

        awaitPublished(behindPoison.eventId());
        OutboxInvariants.verifyAll(ledger, deliveries(), jdbc, consumer, 0);
    }

    @Test
    void repairingAParkedRowReplaysItAheadOfEverythingQueuedBehindIt() {
        String orgId = newOrg();
        OrgMemberAdded parked = memberAdded(orgId);
        OrgMemberAdded oversize = new OrgMemberAdded(
                parked.eventId(),
                parked.eventType(),
                parked.orgId(),
                parked.occurredAt(),
                parked.userId(),
                "x".repeat(OVERSIZE_CHARACTERS));
        insertRaw(parked.eventId(), orgId, OrgMemberAdded.TYPE, jsonMapper.writeValueAsString(oversize));
        ledger.committed(parked);
        OrgMemberAdded behindFirst = memberAdded(orgId);
        OrgMemberAdded behindSecond = memberAdded(orgId);
        commit(behindFirst, behindSecond);
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(statusOf(parked.eventId())).isEqualTo("PARKED"));
        assertThat(statusOf(behindFirst.eventId())).isEqualTo("PENDING");

        jdbc.update(
                "UPDATE org_team.outbox_events SET payload = CAST(? AS jsonb), status = 'PENDING', attempts = 0, "
                        + "next_attempt_at = now(), last_error = NULL WHERE event_id = ?",
                jsonMapper.writeValueAsString(parked),
                parked.eventId());

        awaitDrained(List.of(orgId));
        OutboxInvariants.verifyAll(ledger, deliveries(), jdbc, consumer, 0);
    }

    @Test
    void aSensitivePayloadIsNotScrubbedByAnAmbiguousPublishAndIsScrubbedOnceMarked() {
        String orgId = newOrg();
        NotificationRequested event = NotificationRequested.of(
                orgId,
                "ORG_INVITE",
                "invitee@example.com",
                "EMAIL",
                "invite:chaos:1",
                Map.of("acceptUrl", "https://x/tok"));

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), jsonMapper, NotificationRequested.TYPE)) {
            try (Fault ignored = track(postgres.failOutboxWrites(Statement.MARK_PUBLISHED, orgId))) {
                transaction.executeWithoutResult(status -> writer.append(event, true));

                List<Received> copies = probe.awaitCount(orgId, 2);
                assertThat(copies).hasSizeGreaterThanOrEqualTo(2);
                assertThat(payloadIsNull(event.eventId()))
                        .as("the secret must survive until a publish is recorded, or a retry would send nothing")
                        .isFalse();
            }

            awaitPublished(event.eventId());
            assertThat(payloadIsNull(event.eventId())).isTrue();
            List<Received> all = probe.observe(orgId, Duration.ofSeconds(1));
            assertThat(all)
                    .allSatisfy(copy -> assertThat(copy.body()
                                    .get("variables")
                                    .get("acceptUrl")
                                    .asString())
                            .isEqualTo("https://x/tok"));
            assertThat(all.stream().map(Received::eventId).distinct()).containsExactly(event.eventId());
        }
    }

    private UUID insertPoison(Poison poison, String orgId) {
        UUID eventId = UUID.randomUUID();
        switch (poison) {
            case UNKNOWN_EVENT_TYPE -> insertRaw(eventId, orgId, "bogus.event", "{}");
            case MALFORMED_PAYLOAD -> insertRaw(eventId, orgId, OrgMemberAdded.TYPE, "\"not-an-object\"");
            case MISSING_PAYLOAD -> insertRaw(eventId, orgId, OrgMemberAdded.TYPE, null);
            case OVERSIZE_RECORD ->
                insertRaw(
                        eventId,
                        orgId,
                        OrgMemberAdded.TYPE,
                        jsonMapper.writeValueAsString(new OrgMemberAdded(
                                eventId,
                                OrgMemberAdded.TYPE,
                                orgId,
                                Instant.now(),
                                "user-x",
                                "x".repeat(OVERSIZE_CHARACTERS))));
        }
        return eventId;
    }

    private void insertRaw(UUID eventId, String orgId, String eventType, String payloadJson) {
        jdbc.update(
                "INSERT INTO org_team.outbox_events (event_id, org_id, event_type, payload) "
                        + "VALUES (?, ?, ?, CAST(? AS jsonb))",
                eventId,
                orgId,
                eventType,
                payloadJson);
    }

    private String lastError(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT last_error FROM org_team.outbox_events WHERE event_id = ?", String.class, eventId);
    }

    private boolean payloadIsNull(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT payload IS NULL FROM org_team.outbox_events WHERE event_id = ?", Boolean.class, eventId));
    }
}
