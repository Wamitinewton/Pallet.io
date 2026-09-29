package io.pallet.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GitIntegrationEventsTest {

    private static final String ORG = "org_9k2j7f";
    private static final UUID EVENT_ID = UUID.fromString("0f8e7c52-3b1d-5a9e-8c4f-6d2b1a0e9f73");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-28T08:30:00Z");

    private final JsonMapper json = JsonMapper.builder().build();

    private final GitPushReceived push = GitPushReceived.of(
            EVENT_ID,
            ORG,
            OCCURRED_AT,
            new GitPushReceived.Push(
                    "acme/web",
                    "main",
                    "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
                    "5b1f6f0e-9d0c-4c57-9a55-0d2a1f7a9c11",
                    GitPushReceived.PROVIDER_GITHUB,
                    41_234_567L,
                    812_345_678L,
                    "apps/web",
                    "0000000000000000000000000000000000000000",
                    true,
                    GitPushReceived.TRIGGER_WEBHOOK,
                    "Fix the login redirect",
                    "octocat",
                    "72d3162e-cc78-11e3-81ab-4c9367dc0958"));

    private final OrgMembershipChanged membership =
            OrgMembershipChanged.of(ORG, "usr_1", "developer", OrgMembershipChanged.STATUS_ACTIVE, 7);

    @Test
    void gitPushFactoryKeepsTheSuppliedIdentityAndTime() {
        assertThat(push.eventId()).isEqualTo(EVENT_ID);
        assertThat(push.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(push.eventType()).isEqualTo(Topics.GIT_PUSH_RECEIVED);
        assertThat(push.orgId()).isEqualTo(ORG);
        assertThat(EventType.topicFor(push)).isEqualTo("git.push.received");
    }

    @Test
    void gitPushIsFlatWithTheWireNamesAndRoundTrips() {
        assertThat(propertyNames(push))
                .containsExactlyInAnyOrder(
                        "eventId",
                        "eventType",
                        "orgId",
                        "occurredAt",
                        "repo",
                        "branch",
                        "commitSha",
                        "appId",
                        "provider",
                        "installationId",
                        "repoId",
                        "rootDirectory",
                        "beforeSha",
                        "forced",
                        "trigger",
                        "headCommitMessage",
                        "headCommitAuthor",
                        "deliveryId");
        assertThat(roundTrip(push, GitPushReceived.class)).isEqualTo(push);
    }

    @Test
    void gitPushConstantsAreTheWireValues() {
        assertThat(GitPushReceived.PROVIDER_GITHUB).isEqualTo("GITHUB");
        assertThat(List.of(
                        GitPushReceived.TRIGGER_WEBHOOK,
                        GitPushReceived.TRIGGER_MANUAL,
                        GitPushReceived.TRIGGER_LINKED,
                        GitPushReceived.TRIGGER_RECONCILED))
                .containsExactly("WEBHOOK", "MANUAL", "LINKED", "RECONCILED");
    }

    @Test
    void theOldShapeDeserializesWithTheNewFieldsEmpty() {
        String oldShape = """
            {"eventId":"b4b6c2b0-6d90-4f7e-9c3a-4a2f9b8e11d0","eventType":"git.push.received",
             "orgId":"org_9k2j7f","occurredAt":"2026-09-04T10:15:30Z","repo":"acme/web",
             "branch":"main","commitSha":"a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"}
            """;

        GitPushReceived event = json.readValue(oldShape, GitPushReceived.class);

        assertThat(event.repo()).isEqualTo("acme/web");
        assertThat(event.branch()).isEqualTo("main");
        assertThat(event.commitSha()).isEqualTo("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3");
        assertThat(event.appId()).isNull();
        assertThat(event.provider()).isNull();
        assertThat(event.installationId()).isZero();
        assertThat(event.repoId()).isZero();
        assertThat(event.rootDirectory()).isNull();
        assertThat(event.beforeSha()).isNull();
        assertThat(event.forced()).isFalse();
        assertThat(event.trigger()).isNull();
        assertThat(event.headCommitMessage()).isNull();
        assertThat(event.headCommitAuthor()).isNull();
        assertThat(event.deliveryId()).isNull();
    }

    @Test
    void gitPushIgnoresUnknownProperties() {
        String withUnknown = """
            {"eventId":"b4b6c2b0-6d90-4f7e-9c3a-4a2f9b8e11d0","eventType":"git.push.received",
             "orgId":"org_9k2j7f","occurredAt":"2026-09-04T10:15:30Z","repo":"acme/web",
             "branch":"main","commitSha":"a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
             "appId":"app_1","repoId":42,"somethingNew":{"nested":true}}
            """;

        GitPushReceived event = json.readValue(withUnknown, GitPushReceived.class);

        assertThat(event.appId()).isEqualTo("app_1");
        assertThat(event.repoId()).isEqualTo(42L);
    }

    @Test
    @SuppressWarnings("removal")
    void theDeprecatedFactoryStillStampsIdentityAndLeavesTheNewFieldsEmpty() {
        GitPushReceived legacy =
                GitPushReceived.of(ORG, "acme/web", "main", "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3");

        assertThat(legacy.eventId()).isNotNull();
        assertThat(legacy.eventType()).isEqualTo(GitPushReceived.TYPE);
        assertThat(Duration.between(legacy.occurredAt(), Instant.now())).isLessThan(Duration.ofSeconds(5));
        assertThat(legacy.appId()).isNull();
        assertThat(legacy.installationId()).isZero();
        assertThat(legacy.forced()).isFalse();
    }

    @Test
    void membershipFactoryStampsTypeIdentityAndTime() {
        assertThat(membership.eventId()).isNotNull();
        assertThat(membership.eventType()).isEqualTo(Topics.ORG_MEMBERSHIP_CHANGED);
        assertThat(EventType.topicFor(membership)).isEqualTo("org.membership.changed");
        assertThat(Duration.between(membership.occurredAt(), Instant.now())).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void membershipKeyJoinsOrgAndUser() {
        assertThat(OrgMembershipChanged.key("o1", "u1")).isEqualTo("o1:u1");
    }

    @Test
    void membershipStatusConstantsAreTheWireValues() {
        assertThat(List.of(OrgMembershipChanged.STATUS_ACTIVE, OrgMembershipChanged.STATUS_REMOVED))
                .containsExactly("ACTIVE", "REMOVED");
    }

    @Test
    void membershipIsFlatAndRoundTrips() {
        assertThat(propertyNames(membership))
                .containsExactlyInAnyOrder(
                        "eventId", "eventType", "orgId", "occurredAt", "userId", "role", "status", "membershipVersion");
        assertThat(roundTrip(membership, OrgMembershipChanged.class)).isEqualTo(membership);
    }

    private Iterable<String> propertyNames(PlatformEvent event) {
        JsonNode tree = json.readTree(json.writeValueAsString(event));
        return tree.propertyNames();
    }

    private <T> T roundTrip(T event, Class<T> type) {
        return json.readValue(json.writeValueAsString(event), type);
    }
}
