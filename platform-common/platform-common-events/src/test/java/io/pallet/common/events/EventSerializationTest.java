package io.pallet.common.events;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventSerializationTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void deployStateChangedIsFlatAndRoundTrips() {
        DeployStateChanged event = DeployStateChanged.of("org_9k2j7f", "dep_4f8a21", "BUILDING", "ROUTING");

        JsonNode tree = json.readTree(json.writeValueAsString(event));
        assertThat(tree.propertyNames()).containsExactlyInAnyOrder(
            "eventId", "eventType", "orgId", "occurredAt", "deploymentId", "fromState", "toState");

        assertThat(json.readValue(json.writeValueAsString(event), DeployStateChanged.class)).isEqualTo(event);
    }

    @Test
    void notificationRequestedIsFlatAndRoundTrips() {
        NotificationRequested event = NotificationRequested.of(
            "org_9k2j7f", "ORG_OWNER_WELCOME", "owner@acme.test", "EMAIL", "welcome:usr_1",
            Map.of("name", "Ada"));

        JsonNode tree = json.readTree(json.writeValueAsString(event));
        assertThat(tree.propertyNames()).containsExactlyInAnyOrder(
            "eventId", "eventType", "orgId", "occurredAt",
            "notificationType", "recipient", "channel", "dedupeKey", "variables");

        assertThat(json.readValue(json.writeValueAsString(event), NotificationRequested.class)).isEqualTo(event);
    }

    @Test
    void occurredAtRoundTripsAsIso8601Utc() {
        DeployStateChanged event = DeployStateChanged.of("org_9k2j7f", "dep_4f8a21", "BUILDING", "ROUTING");

        String occurredAt = json.readTree(json.writeValueAsString(event)).get("occurredAt").asString();

        assertThat(occurredAt).endsWith("Z");
        assertThat(json.readValue(json.writeValueAsString(event), DeployStateChanged.class).occurredAt())
            .isEqualTo(event.occurredAt());
    }

    @Test
    void unknownFieldsAreIgnored() {
        String withUnknown = """
            {"eventId":"b4b6c2b0-6d90-4f7e-9c3a-4a2f9b8e11d0","eventType":"deploy.state.changed",
             "orgId":"org_9k2j7f","occurredAt":"2026-09-04T10:15:30Z","deploymentId":"dep_4f8a21",
             "fromState":"BUILDING","toState":"ROUTING","somethingNew":true}
            """;

        DeployStateChanged event = json.readValue(withUnknown, DeployStateChanged.class);

        assertThat(event.deploymentId()).isEqualTo("dep_4f8a21");
        assertThat(event.toState()).isEqualTo("ROUTING");
    }
}
