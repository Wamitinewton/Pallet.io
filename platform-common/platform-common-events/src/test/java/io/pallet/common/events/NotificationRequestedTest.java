package io.pallet.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NotificationRequestedTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void factoryStampsEnvelopeAndCarriesDomainFields() {
        NotificationRequested event = NotificationRequested.of(
                "org_9k2j7f", "ORG_OWNER_WELCOME", "owner@acme.test", "EMAIL", "welcome:usr_1", Map.of("name", "Ada"));

        assertThat(event.eventId()).isNotNull();
        assertThat(event.eventType()).isEqualTo(NotificationRequested.TYPE);
        assertThat(event.eventType()).isEqualTo("notification.requested");
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.orgId()).isEqualTo("org_9k2j7f");
        assertThat(event.notificationType()).isEqualTo("ORG_OWNER_WELCOME");
        assertThat(event.recipient()).isEqualTo("owner@acme.test");
        assertThat(event.channel()).isEqualTo("EMAIL");
        assertThat(event.dedupeKey()).isEqualTo("welcome:usr_1");
        assertThat(event.variables()).containsEntry("name", "Ada");
    }

    @Test
    void dedupeKeyMayBeNull() {
        NotificationRequested event =
                NotificationRequested.of("org_9k2j7f", "ORG_OWNER_WELCOME", "owner@acme.test", "EMAIL", null, Map.of());

        assertThat(event.dedupeKey()).isNull();
    }

    @Test
    void nullVariablesBecomeAnEmptyMap() {
        NotificationRequested event =
                NotificationRequested.of("org_9k2j7f", "ORG_OWNER_WELCOME", "owner@acme.test", "EMAIL", null, null);

        assertThat(event.variables()).isNotNull().isEmpty();
    }

    @Test
    void absentAudienceDefaultsToSingle() {
        NotificationRequested event = new NotificationRequested(
                UUID.randomUUID(),
                NotificationRequested.TYPE,
                "org_9k2j7f",
                Instant.now(),
                "ORG_OWNER_WELCOME",
                "owner@acme.test",
                "EMAIL",
                null,
                Map.of(),
                null);

        assertThat(event.audience()).isEqualTo(NotificationRequested.AUDIENCE_SINGLE);
    }

    @Test
    void blankAudienceDefaultsToSingle() {
        NotificationRequested event = new NotificationRequested(
                UUID.randomUUID(),
                NotificationRequested.TYPE,
                "org_9k2j7f",
                Instant.now(),
                "ORG_OWNER_WELCOME",
                "owner@acme.test",
                "EMAIL",
                null,
                Map.of(),
                "  ");

        assertThat(event.audience()).isEqualTo(NotificationRequested.AUDIENCE_SINGLE);
    }

    @Test
    void explicitOrgAudienceIsPreserved() {
        NotificationRequested event = new NotificationRequested(
                UUID.randomUUID(),
                NotificationRequested.TYPE,
                "org_9k2j7f",
                Instant.now(),
                "ORG_OWNER_WELCOME",
                "owner@acme.test",
                "EMAIL",
                null,
                Map.of(),
                NotificationRequested.AUDIENCE_ORG);

        assertThat(event.audience()).isEqualTo(NotificationRequested.AUDIENCE_ORG);
    }

    @Test
    void ofProducesSingleAudience() {
        NotificationRequested event =
                NotificationRequested.of("org_9k2j7f", "ORG_OWNER_WELCOME", "owner@acme.test", "EMAIL", null, Map.of());

        assertThat(event.audience()).isEqualTo(NotificationRequested.AUDIENCE_SINGLE);
    }

    @Test
    void broadcastProducesOrgAudienceWithNoRecipientOrChannel() {
        NotificationRequested event =
                NotificationRequested.broadcast("org_9k2j7f", "ORG_ANNOUNCEMENT", "announce:1", Map.of("title", "Hi"));

        assertThat(event.audience()).isEqualTo(NotificationRequested.AUDIENCE_ORG);
        assertThat(event.recipient()).isNull();
        assertThat(event.channel()).isNull();
        assertThat(event.orgId()).isEqualTo("org_9k2j7f");
        assertThat(event.dedupeKey()).isEqualTo("announce:1");
        assertThat(event.variables()).containsEntry("title", "Hi");
    }

    @Test
    void audienceSurvivesAJsonRoundTrip() {
        NotificationRequested event =
                NotificationRequested.broadcast("org_9k2j7f", "ORG_ANNOUNCEMENT", "announce:1", Map.of("title", "Hi"));

        String json = mapper.writeValueAsString(event);
        NotificationRequested roundTripped = mapper.readValue(json, NotificationRequested.class);

        assertThat(roundTripped.audience()).isEqualTo(NotificationRequested.AUDIENCE_ORG);
    }

    @Test
    void deserializingAPayloadWithNoAudienceKeyDefaultsToSingle() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "notification.requested",
                  "orgId": "org_9k2j7f",
                  "occurredAt": "2024-01-01T00:00:00Z",
                  "notificationType": "ORG_OWNER_WELCOME",
                  "recipient": "owner@acme.test",
                  "channel": "EMAIL",
                  "dedupeKey": null,
                  "variables": {}
                }
                """.formatted(UUID.randomUUID());

        NotificationRequested event = mapper.readValue(json, NotificationRequested.class);

        assertThat(event.audience()).isEqualTo(NotificationRequested.AUDIENCE_SINGLE);
    }
}
