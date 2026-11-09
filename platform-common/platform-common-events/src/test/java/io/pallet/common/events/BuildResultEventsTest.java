package io.pallet.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BuildResultEventsTest {

    private static final String ORG = "org_9k2j7f";
    private static final String APP = "5b1f6f0e-9d0c-4c57-9a55-0d2a1f7a9c11";
    private static final String BUILD = "7d4c2a90-1e5b-4f3a-8c6d-2b9e0f1a3c55";
    private static final String SHA = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";
    private static final UUID PUSH = UUID.fromString("0f8e7c52-3b1d-5a9e-8c4f-6d2b1a0e9f73");
    private static final UUID EVENT_ID = UUID.fromString("3c8f1a52-7e4d-5b9a-9c2e-1f6d0a8b4e37");
    private static final Instant OCCURRED_AT = Instant.parse("2026-11-09T10:00:00Z");
    private static final String DIGEST = "sha256:" + "4f".repeat(32);

    private static final List<String> ENVELOPE_AND_ORIGINAL =
            List.of("eventId", "eventType", "orgId", "occurredAt", "buildId", "appId", "commitSha", "pushEventId");

    private final JsonMapper json = JsonMapper.builder().build();

    private final BuildStarted started =
            BuildStarted.of(EVENT_ID, ORG, OCCURRED_AT, new BuildStarted.Started(BUILD, APP, SHA, PUSH, 2, "main"));

    private final BuildSucceeded succeeded = BuildSucceeded.of(
            EVENT_ID,
            ORG,
            OCCURRED_AT,
            new BuildSucceeded.Succeeded(
                    BUILD,
                    APP,
                    SHA,
                    PUSH,
                    "main",
                    BuildSucceeded.STRATEGY_DOCKERFILE,
                    "pallet-registry:5000/org_9k2j7f/" + APP,
                    "a94a8fe5ccb1-7d4c2a90",
                    DIGEST,
                    184_250L));

    private final BuildFailed failed = BuildFailed.of(
            EVENT_ID,
            ORG,
            OCCURRED_AT,
            new BuildFailed.Failed(
                    BUILD,
                    APP,
                    SHA,
                    PUSH,
                    "The build step exited with code 1.",
                    "main",
                    BuildFailed.CODE_BUILD_FAILED,
                    BuildFailed.STEP_BUILD,
                    61_002L));

    @Test
    void factoriesKeepTheSuppliedIdentityAndTimeAndStampTheirTopic() {
        assertThat(List.<PlatformEvent>of(started, succeeded, failed)).allSatisfy(event -> {
            assertThat(event.eventId()).isEqualTo(EVENT_ID);
            assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
            assertThat(event.orgId()).isEqualTo(ORG);
        });
        assertThat(started.eventType()).isEqualTo(Topics.BUILD_STARTED).isEqualTo("build.started");
        assertThat(succeeded.eventType()).isEqualTo(Topics.BUILD_SUCCEEDED).isEqualTo("build.succeeded");
        assertThat(failed.eventType()).isEqualTo(Topics.BUILD_FAILED).isEqualTo("build.failed");
        assertThat(EventType.topicFor(started)).isEqualTo(Topics.BUILD_STARTED);
        assertThat(EventType.topicFor(succeeded)).isEqualTo(Topics.BUILD_SUCCEEDED);
        assertThat(EventType.topicFor(failed)).isEqualTo(Topics.BUILD_FAILED);
    }

    @Test
    void everyRecordRoundTripsWithEveryFieldEqual() {
        assertThat(roundTrip(started, BuildStarted.class)).isEqualTo(started);
        assertThat(roundTrip(succeeded, BuildSucceeded.class)).isEqualTo(succeeded);
        assertThat(roundTrip(failed, BuildFailed.class)).isEqualTo(failed);
    }

    @Test
    void buildStartedCarriesExactlyTheDocumentedFields() {
        assertThat(propertyNames(started)).containsExactlyInAnyOrderElementsOf(withOriginalFields("attempt", "branch"));
    }

    @Test
    void buildSucceededCarriesExactlyTheDocumentedFields() {
        assertThat(propertyNames(succeeded))
                .containsExactlyInAnyOrderElementsOf(withOriginalFields(
                        "branch", "strategy", "imageRepository", "imageTag", "imageDigest", "durationMs"));
    }

    @Test
    void buildFailedCarriesExactlyTheDocumentedFields() {
        assertThat(propertyNames(failed))
                .containsExactlyInAnyOrderElementsOf(
                        withOriginalFields("reason", "branch", "failureCode", "failedStep", "durationMs"));
    }

    @Test
    void aPre016BuildStartedReadsWithTheNewFieldsEmpty() {
        BuildStarted event = json.readValue(preReleasePayload("build.started", ""), BuildStarted.class);

        assertThat(event.buildId()).isEqualTo(BUILD);
        assertThat(event.pushEventId()).isEqualTo(PUSH);
        assertThat(event.attempt()).isZero();
        assertThat(event.branch()).isNull();
    }

    @Test
    void aPre016BuildSucceededReadsWithTheNewFieldsEmpty() {
        BuildSucceeded event = json.readValue(preReleasePayload("build.succeeded", ""), BuildSucceeded.class);

        assertThat(event.commitSha()).isEqualTo(SHA);
        assertThat(event.branch()).isNull();
        assertThat(event.strategy()).isNull();
        assertThat(event.imageRepository()).isNull();
        assertThat(event.imageTag()).isNull();
        assertThat(event.imageDigest()).isNull();
        assertThat(event.durationMs()).isZero();
    }

    @Test
    void aPre016BuildFailedReadsWithTheNewFieldsEmpty() {
        BuildFailed event = json.readValue(
                preReleasePayload("build.failed", ",\"reason\":\"Dockerfile not found\""), BuildFailed.class);

        assertThat(event.reason()).isEqualTo("Dockerfile not found");
        assertThat(event.branch()).isNull();
        assertThat(event.failureCode()).isNull();
        assertThat(event.failedStep()).isNull();
        assertThat(event.durationMs()).isZero();
    }

    @Test
    void explicitNullPrimitivesReadAsZero() {
        String startedWithNull = preReleasePayload("build.started", ",\"attempt\":null");
        String failedWithNull = preReleasePayload("build.failed", ",\"durationMs\":null");

        assertThat(json.readValue(startedWithNull, BuildStarted.class).attempt())
                .isZero();
        assertThat(json.readValue(failedWithNull, BuildFailed.class).durationMs())
                .isZero();
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedFactoriesStillProduceAValidEventWithARandomId() {
        BuildStarted oldStarted = BuildStarted.of(ORG, BUILD, APP, SHA, PUSH);
        BuildSucceeded oldSucceeded = BuildSucceeded.of(ORG, BUILD, APP, SHA, PUSH);
        BuildFailed oldFailed = BuildFailed.of(ORG, BUILD, APP, SHA, PUSH, "Dockerfile not found");

        assertThat(List.<PlatformEvent>of(oldStarted, oldSucceeded, oldFailed)).allSatisfy(event -> {
            assertThat(event.eventId()).isNotNull().isNotEqualTo(EVENT_ID);
            assertThat(event.occurredAt()).isNotNull();
            assertThat(event.orgId()).isEqualTo(ORG);
        });
        assertThat(BuildStarted.of(ORG, BUILD, APP, SHA, PUSH).eventId()).isNotEqualTo(oldStarted.eventId());
        assertThat(oldStarted.eventType()).isEqualTo(Topics.BUILD_STARTED);
        assertThat(oldSucceeded.eventType()).isEqualTo(Topics.BUILD_SUCCEEDED);
        assertThat(oldFailed.eventType()).isEqualTo(Topics.BUILD_FAILED);
        assertThat(oldStarted.attempt()).isZero();
        assertThat(oldSucceeded.imageDigest()).isNull();
        assertThat(oldFailed.reason()).isEqualTo("Dockerfile not found");
        assertThat(oldFailed.failureCode()).isNull();
        assertThat(roundTrip(oldFailed, BuildFailed.class)).isEqualTo(oldFailed);
    }

    @Test
    void failureCodesStepsAndStrategiesAreTheWireValues() {
        assertThat(List.of(
                        BuildFailed.CODE_SOURCE_UNAVAILABLE,
                        BuildFailed.CODE_SOURCE_NOT_FOUND,
                        BuildFailed.CODE_DETECTION_FAILED,
                        BuildFailed.CODE_BUILD_FAILED,
                        BuildFailed.CODE_RESOURCE_LIMIT,
                        BuildFailed.CODE_PUSH_FAILED,
                        BuildFailed.CODE_TIMEOUT,
                        BuildFailed.CODE_LAUNCH_FAILED,
                        BuildFailed.CODE_INFRASTRUCTURE,
                        BuildFailed.CODE_APP_DELETED,
                        BuildFailed.CODE_ORG_DELETED))
                .containsExactly(
                        "SOURCE_UNAVAILABLE",
                        "SOURCE_NOT_FOUND",
                        "DETECTION_FAILED",
                        "BUILD_FAILED",
                        "RESOURCE_LIMIT",
                        "PUSH_FAILED",
                        "TIMEOUT",
                        "LAUNCH_FAILED",
                        "INFRASTRUCTURE",
                        "APP_DELETED",
                        "ORG_DELETED");
        assertThat(List.of(BuildFailed.STEP_FETCH, BuildFailed.STEP_BUILD, BuildFailed.STEP_PUSH))
                .containsExactly("FETCH", "BUILD", "PUSH");
        assertThat(List.of(BuildSucceeded.STRATEGY_DOCKERFILE, BuildSucceeded.STRATEGY_BUILDPACKS))
                .containsExactly("DOCKERFILE", "BUILDPACKS");
    }

    private static List<String> withOriginalFields(String... added) {
        return Stream.concat(ENVELOPE_AND_ORIGINAL.stream(), Arrays.stream(added))
                .toList();
    }

    private static String preReleasePayload(String eventType, String extra) {
        return """
            {"eventId":"b4b6c2b0-6d90-4f7e-9c3a-4a2f9b8e11d0","eventType":"%s","orgId":"org_9k2j7f",
             "occurredAt":"2026-10-15T09:00:00Z","buildId":"7d4c2a90-1e5b-4f3a-8c6d-2b9e0f1a3c55",
             "appId":"5b1f6f0e-9d0c-4c57-9a55-0d2a1f7a9c11","commitSha":"a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
             "pushEventId":"0f8e7c52-3b1d-5a9e-8c4f-6d2b1a0e9f73"%s}
            """.formatted(eventType, extra);
    }

    private Iterable<String> propertyNames(PlatformEvent event) {
        JsonNode tree = json.readTree(json.writeValueAsString(event));
        return tree.propertyNames();
    }

    private <T> T roundTrip(T event, Class<T> type) {
        return json.readValue(json.writeValueAsString(event), type);
    }
}
