package io.pallet.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeployStateChangedTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void factoryStampsEnvelopeFields() {
        DeployStateChanged event = DeployStateChanged.of("org_9k2j7f", "dep_4f8a21", "BUILDING", "ROUTING");

        assertThat(event.eventId()).isNotNull();
        assertThat(event.eventType()).isEqualTo("deploy.state.changed");
        assertThat(event.orgId()).isEqualTo("org_9k2j7f");
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.fromState()).isEqualTo("BUILDING");
        assertThat(event.toState()).isEqualTo("ROUTING");
    }

    @Test
    void carriesTheCommitAndLiveUrlAndRoundTrips() {
        DeployStateChanged event = DeployStateChanged.of(
                "org_9k2j7f",
                "dep_4f8a21",
                "HEALTH_CHECKING",
                "LIVE",
                "5b1f6f0e-9d0c-4c57-9a55-0d2a1f7a9c11",
                "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
                "https://web.acme.app");

        assertThat(event.appId()).isEqualTo("5b1f6f0e-9d0c-4c57-9a55-0d2a1f7a9c11");
        assertThat(event.commitSha()).isEqualTo("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3");
        assertThat(event.url()).isEqualTo("https://web.acme.app");
        assertThat(json.readValue(json.writeValueAsString(event), DeployStateChanged.class))
                .isEqualTo(event);
    }

    @Test
    void theOldShapeDeserializesWithTheNewFieldsNull() {
        String oldShape = """
            {"eventId":"b4b6c2b0-6d90-4f7e-9c3a-4a2f9b8e11d0","eventType":"deploy.state.changed",
             "orgId":"org_9k2j7f","deploymentId":"dep_4f8a21","fromState":"BUILDING","toState":"ROUTING",
             "occurredAt":"2026-09-04T10:15:30Z"}
            """;

        DeployStateChanged event = json.readValue(oldShape, DeployStateChanged.class);

        assertThat(event.toState()).isEqualTo("ROUTING");
        assertThat(event.appId()).isNull();
        assertThat(event.commitSha()).isNull();
        assertThat(event.url()).isNull();
    }
}
