package io.pallet.common.events;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRequestedTest {

    @Test
    void factoryStampsEnvelopeAndCarriesDomainFields() {
        NotificationRequested event = NotificationRequested.of(
                "org_9k2j7f", "ORG_OWNER_WELCOME", "owner@acme.test", "EMAIL",
                "welcome:usr_1", Map.of("name", "Ada"));

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
        NotificationRequested event = NotificationRequested.of(
                "org_9k2j7f", "ORG_OWNER_WELCOME", "owner@acme.test", "EMAIL", null, Map.of());

        assertThat(event.dedupeKey()).isNull();
    }

    @Test
    void nullVariablesBecomeAnEmptyMap() {
        NotificationRequested event = NotificationRequested.of(
                "org_9k2j7f", "ORG_OWNER_WELCOME", "owner@acme.test", "EMAIL", null, null);

        assertThat(event.variables()).isNotNull().isEmpty();
    }
}
