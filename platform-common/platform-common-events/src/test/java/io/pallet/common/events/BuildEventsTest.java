package io.pallet.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BuildEventsTest {

    private static final String ORG = "org_9k2j7f";
    private static final String APP = "5b1f6f0e-9d0c-4c57-9a55-0d2a1f7a9c11";
    private static final String SHA = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";
    private static final UUID PUSH = UUID.fromString("0f8e7c52-3b1d-5a9e-8c4f-6d2b1a0e9f73");

    private final JsonMapper json = JsonMapper.builder().build();

    private final BuildStarted started = BuildStarted.of(ORG, "bld_1", APP, SHA, PUSH);
    private final BuildSucceeded succeeded = BuildSucceeded.of(ORG, "bld_1", APP, SHA, PUSH);
    private final BuildFailed failed = BuildFailed.of(ORG, "bld_1", APP, SHA, PUSH, "Dockerfile not found");

    @Test
    void factoriesStampTheTypeOfTheirTopic() {
        assertThat(List.<PlatformEvent>of(started, succeeded, failed)).allSatisfy(event -> {
            assertThat(event.eventId()).isNotNull();
            assertThat(event.occurredAt()).isNotNull();
            assertThat(event.orgId()).isEqualTo(ORG);
        });
        assertThat(EventType.topicFor(started)).isEqualTo("build.started");
        assertThat(EventType.topicFor(succeeded)).isEqualTo("build.succeeded");
        assertThat(EventType.topicFor(failed)).isEqualTo("build.failed");
    }

    @Test
    void buildEventsCarryTheCommitTheyBuiltAndRoundTrip() {
        List<String> common =
                List.of("eventId", "eventType", "orgId", "occurredAt", "buildId", "appId", "commitSha", "pushEventId");
        assertThat(propertyNames(started)).containsExactlyInAnyOrderElementsOf(common);
        assertThat(propertyNames(succeeded)).containsExactlyInAnyOrderElementsOf(common);
        assertThat(propertyNames(failed))
                .hasSize(common.size() + 1)
                .containsAll(common)
                .contains("reason");

        assertThat(json.readValue(json.writeValueAsString(started), BuildStarted.class))
                .isEqualTo(started);
        assertThat(json.readValue(json.writeValueAsString(succeeded), BuildSucceeded.class))
                .isEqualTo(succeeded);
        assertThat(json.readValue(json.writeValueAsString(failed), BuildFailed.class))
                .isEqualTo(failed);
    }

    @Test
    void deployStateChangedCarriesTheCommitAndLiveUrl() {
        DeployStateChanged event =
                DeployStateChanged.of(ORG, "dep_4f8a21", "HEALTH_CHECKING", "LIVE", APP, SHA, "https://web.acme.app");

        assertThat(event.appId()).isEqualTo(APP);
        assertThat(event.commitSha()).isEqualTo(SHA);
        assertThat(event.url()).isEqualTo("https://web.acme.app");
        assertThat(json.readValue(json.writeValueAsString(event), DeployStateChanged.class))
                .isEqualTo(event);
    }

    @Test
    void theOldDeployStateChangedShapeDeserializesWithTheNewFieldsNull() {
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

    private Iterable<String> propertyNames(PlatformEvent event) {
        JsonNode tree = json.readTree(json.writeValueAsString(event));
        return tree.propertyNames();
    }
}
