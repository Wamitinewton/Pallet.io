package io.pallet.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BuildQueueEventsTest {

    private static final String ORG = "org_9k2j7f";
    private static final String APP = "5b1f6f0e-9d0c-4c57-9a55-0d2a1f7a9c11";
    private static final String BUILD = "7d4c2a90-1e5b-4f3a-8c6d-2b9e0f1a3c55";
    private static final String NEWER_BUILD = "c3e8f1d2-6a4b-4e9c-9f0a-5d7b2c1e8a44";
    private static final String SHA = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";
    private static final UUID PUSH = UUID.fromString("0f8e7c52-3b1d-5a9e-8c4f-6d2b1a0e9f73");
    private static final UUID EVENT_ID = UUID.fromString("9a1b2c3d-4e5f-5a6b-8c7d-0e1f2a3b4c5d");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-15T09:00:00Z");

    private final JsonMapper json = JsonMapper.builder().build();

    private final BuildQueued queued = BuildQueued.of(
            EVENT_ID,
            ORG,
            OCCURRED_AT,
            BUILD,
            APP,
            "main",
            SHA,
            PUSH,
            GitPushReceived.TRIGGER_WEBHOOK,
            "1f2e3d4c-5b6a-4987-8f6e-5d4c3b2a1908");

    private final BuildDispatched dispatched = BuildDispatched.of(
            EVENT_ID,
            ORG,
            OCCURRED_AT,
            new BuildDispatched.Dispatch(
                    BUILD,
                    2,
                    APP,
                    "main",
                    SHA,
                    PUSH,
                    GitPushReceived.TRIGGER_WEBHOOK,
                    GitPushReceived.PROVIDER_GITHUB,
                    41_234_567L,
                    812_345_678L,
                    "acme/web",
                    "apps/web",
                    Instant.parse("2026-10-15T09:05:00Z"),
                    1_800L));

    private final BuildCancelled cancelled = BuildCancelled.of(
            EVENT_ID, ORG, OCCURRED_AT, BUILD, APP, SHA, PUSH, BuildCancelled.REASON_SUPERSEDED, NEWER_BUILD);

    @Test
    void factoriesKeepTheSuppliedIdentityAndTimeAndStampTheirTopic() {
        assertThat(List.<PlatformEvent>of(queued, dispatched, cancelled)).allSatisfy(event -> {
            assertThat(event.eventId()).isEqualTo(EVENT_ID);
            assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
            assertThat(event.orgId()).isEqualTo(ORG);
        });
        assertThat(EventType.topicFor(queued)).isEqualTo(Topics.BUILD_QUEUED).isEqualTo("build.queued");
        assertThat(EventType.topicFor(dispatched))
                .isEqualTo(Topics.BUILD_DISPATCHED)
                .isEqualTo("build.dispatched");
        assertThat(EventType.topicFor(cancelled))
                .isEqualTo(Topics.BUILD_CANCELLED)
                .isEqualTo("build.cancelled");
    }

    @Test
    void everyRecordRoundTripsWithEveryFieldEqual() {
        assertThat(roundTrip(queued, BuildQueued.class)).isEqualTo(queued);
        assertThat(roundTrip(dispatched, BuildDispatched.class)).isEqualTo(dispatched);
        assertThat(roundTrip(cancelled, BuildCancelled.class)).isEqualTo(cancelled);
    }

    @Test
    void nullableLinksRoundTripAsNull() {
        BuildQueued fresh = BuildQueued.of(
                EVENT_ID, ORG, OCCURRED_AT, BUILD, APP, "main", SHA, PUSH, GitPushReceived.TRIGGER_MANUAL, null);
        BuildCancelled byUser = BuildCancelled.of(
                EVENT_ID, ORG, OCCURRED_AT, BUILD, APP, SHA, PUSH, BuildCancelled.REASON_USER_CANCELLED, null);

        assertThat(roundTrip(fresh, BuildQueued.class).supersedes()).isNull();
        assertThat(roundTrip(byUser, BuildCancelled.class).supersededBy()).isNull();
    }

    @Test
    void buildQueuedIsFlatWithTheDocumentedWireNames() {
        assertThat(propertyNames(queued))
                .containsExactlyInAnyOrder(
                        "eventId",
                        "eventType",
                        "orgId",
                        "occurredAt",
                        "buildId",
                        "appId",
                        "branch",
                        "commitSha",
                        "pushEventId",
                        "trigger",
                        "supersedes");
    }

    @Test
    void buildDispatchedCarriesExactlyTheDocumentedFieldsAndNothingSecret() {
        assertThat(propertyNames(dispatched))
                .containsExactlyInAnyOrder(
                        "eventId",
                        "eventType",
                        "orgId",
                        "occurredAt",
                        "buildId",
                        "attempt",
                        "appId",
                        "branch",
                        "commitSha",
                        "pushEventId",
                        "trigger",
                        "provider",
                        "installationId",
                        "repoId",
                        "repo",
                        "rootDirectory",
                        "startBy",
                        "maxDurationSeconds");
    }

    @Test
    void buildCancelledIsFlatWithTheDocumentedWireNames() {
        assertThat(propertyNames(cancelled))
                .containsExactlyInAnyOrder(
                        "eventId",
                        "eventType",
                        "orgId",
                        "occurredAt",
                        "buildId",
                        "appId",
                        "commitSha",
                        "pushEventId",
                        "reason",
                        "supersededBy");
    }

    @Test
    void buildDispatchedReadsMissingPrimitivesAsZero() {
        String withoutPrimitives = """
            {"eventId":"9a1b2c3d-4e5f-5a6b-8c7d-0e1f2a3b4c5d","eventType":"build.dispatched",
             "orgId":"org_9k2j7f","occurredAt":"2026-10-15T09:00:00Z",
             "buildId":"7d4c2a90-1e5b-4f3a-8c6d-2b9e0f1a3c55","appId":"app_1","branch":"main",
             "commitSha":"a94a8fe5ccb19ba61c4c0873d391e987982fbbd3","startBy":"2026-10-15T09:05:00Z"}
            """;

        BuildDispatched event = json.readValue(withoutPrimitives, BuildDispatched.class);

        assertThat(event.buildId()).isEqualTo(BUILD);
        assertThat(event.startBy()).isEqualTo(Instant.parse("2026-10-15T09:05:00Z"));
        assertThat(event.attempt()).isZero();
        assertThat(event.installationId()).isZero();
        assertThat(event.repoId()).isZero();
        assertThat(event.maxDurationSeconds()).isZero();
    }

    @Test
    void buildDispatchedReadsExplicitNullPrimitivesAsZero() {
        String withNulls = """
            {"eventId":"9a1b2c3d-4e5f-5a6b-8c7d-0e1f2a3b4c5d","eventType":"build.dispatched",
             "orgId":"org_9k2j7f","occurredAt":"2026-10-15T09:00:00Z",
             "buildId":"7d4c2a90-1e5b-4f3a-8c6d-2b9e0f1a3c55",
             "attempt":null,"installationId":null,"repoId":null,"maxDurationSeconds":null}
            """;

        BuildDispatched event = json.readValue(withNulls, BuildDispatched.class);

        assertThat(event.attempt()).isZero();
        assertThat(event.installationId()).isZero();
        assertThat(event.repoId()).isZero();
        assertThat(event.maxDurationSeconds()).isZero();
    }

    @Test
    void cancellationReasonsAreTheWireValues() {
        assertThat(List.of(
                        BuildCancelled.REASON_SUPERSEDED,
                        BuildCancelled.REASON_USER_CANCELLED,
                        BuildCancelled.REASON_APP_DELETED,
                        BuildCancelled.REASON_ORG_DELETED,
                        BuildCancelled.REASON_QUEUE_FULL,
                        BuildCancelled.REASON_QUEUE_TIMEOUT,
                        BuildCancelled.REASON_START_TIMEOUT,
                        BuildCancelled.REASON_LEASE_EXPIRED))
                .containsExactly(
                        "SUPERSEDED",
                        "USER_CANCELLED",
                        "APP_DELETED",
                        "ORG_DELETED",
                        "QUEUE_FULL",
                        "QUEUE_TIMEOUT",
                        "START_TIMEOUT",
                        "LEASE_EXPIRED");
    }

    private Iterable<String> propertyNames(PlatformEvent event) {
        JsonNode tree = json.readTree(json.writeValueAsString(event));
        return tree.propertyNames();
    }

    private <T> T roundTrip(T event, Class<T> type) {
        return json.readValue(json.writeValueAsString(event), type);
    }
}
