package io.pallet.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OrgTeamEventsTest {

    private static final String ORG = "org_9k2j7f";

    private final JsonMapper json = JsonMapper.builder().build();

    private final OrgMemberAdded memberAdded = OrgMemberAdded.of(ORG, "usr_1", "dev@acme.test");
    private final OrgInviteRejected inviteRejected = OrgInviteRejected.of(
            ORG, "9e6f1c2a-4b3d-4a7e-8f1a-2c3d4e5f6a7b", "usr_2", "dev@acme.test", OrgInviteRejected.REASON_REVOKED);
    private final AppCreated appCreated =
            AppCreated.of(ORG, "5b1f6f0e-9d0c-4c57-9a55-0d2a1f7a9c11", "Web", "web", null, "AWS", "eu-west-1", "usr_1");
    private final AppDeleted appDeleted = AppDeleted.of(ORG, "5b1f6f0e-9d0c-4c57-9a55-0d2a1f7a9c11", "web", "usr_1");

    @Test
    void ofStampsTypeIdentityAndTime() {
        assertThat(List.<PlatformEvent>of(memberAdded, inviteRejected, appCreated, appDeleted))
                .allSatisfy(event -> {
                    assertThat(event.eventId()).isNotNull();
                    assertThat(event.orgId()).isEqualTo(ORG);
                    assertThat(Duration.between(event.occurredAt(), Instant.now()))
                            .isLessThan(Duration.ofSeconds(5));
                });
        assertThat(memberAdded.eventType()).isEqualTo(Topics.ORG_MEMBER_ADDED);
        assertThat(inviteRejected.eventType()).isEqualTo(Topics.ORG_INVITE_REJECTED);
        assertThat(appCreated.eventType()).isEqualTo(Topics.APP_CREATED);
        assertThat(appDeleted.eventType()).isEqualTo(Topics.APP_DELETED);
    }

    @Test
    void topicForResolvesTheCatalogTopic() {
        assertThat(EventType.topicFor(memberAdded)).isEqualTo("org.member.added");
        assertThat(EventType.topicFor(inviteRejected)).isEqualTo("org.invite.rejected");
        assertThat(EventType.topicFor(appCreated)).isEqualTo("app.created");
        assertThat(EventType.topicFor(appDeleted)).isEqualTo("app.deleted");
    }

    @Test
    void orgMemberAddedIsFlatAndRoundTrips() {
        assertThat(propertyNames(memberAdded))
                .containsExactlyInAnyOrder("eventId", "eventType", "orgId", "occurredAt", "userId", "email");
        assertThat(roundTrip(memberAdded, OrgMemberAdded.class)).isEqualTo(memberAdded);
    }

    @Test
    void orgInviteRejectedIsFlatAndRoundTrips() {
        assertThat(propertyNames(inviteRejected))
                .containsExactlyInAnyOrder(
                        "eventId", "eventType", "orgId", "occurredAt", "inviteId", "userId", "email", "reason");
        assertThat(roundTrip(inviteRejected, OrgInviteRejected.class)).isEqualTo(inviteRejected);
    }

    @Test
    void orgInviteRejectedReasonConstantsAreTheWireValues() {
        assertThat(List.of(
                        OrgInviteRejected.REASON_REVOKED,
                        OrgInviteRejected.REASON_EXPIRED,
                        OrgInviteRejected.REASON_UNKNOWN_INVITE,
                        OrgInviteRejected.REASON_ORG_DELETED))
                .containsExactly("REVOKED", "EXPIRED", "UNKNOWN_INVITE", "ORG_DELETED");
    }

    @Test
    void appCreatedIsFlatAndRoundTripsWithANullTeam() {
        assertThat(propertyNames(appCreated))
                .containsExactlyInAnyOrder(
                        "eventId",
                        "eventType",
                        "orgId",
                        "occurredAt",
                        "appId",
                        "name",
                        "slug",
                        "teamId",
                        "cloudProvider",
                        "region",
                        "createdByUserId");
        AppCreated restored = roundTrip(appCreated, AppCreated.class);
        assertThat(restored).isEqualTo(appCreated);
        assertThat(restored.teamId()).isNull();
    }

    @Test
    void appDeletedIsFlatAndRoundTrips() {
        assertThat(propertyNames(appDeleted))
                .containsExactlyInAnyOrder(
                        "eventId", "eventType", "orgId", "occurredAt", "appId", "slug", "deletedByUserId");
        assertThat(roundTrip(appDeleted, AppDeleted.class)).isEqualTo(appDeleted);
    }

    @Test
    void unknownFieldsAreIgnored() {
        String withUnknown = """
            {"eventId":"b4b6c2b0-6d90-4f7e-9c3a-4a2f9b8e11d0","eventType":"org.member.added",
             "orgId":"org_9k2j7f","occurredAt":"2026-09-04T10:15:30Z","userId":"usr_1",
             "email":"dev@acme.test","somethingNew":true}
            """;

        OrgMemberAdded event = json.readValue(withUnknown, OrgMemberAdded.class);

        assertThat(event.userId()).isEqualTo("usr_1");
        assertThat(event.email()).isEqualTo("dev@acme.test");
    }

    private Iterable<String> propertyNames(PlatformEvent event) {
        JsonNode tree = json.readTree(json.writeValueAsString(event));
        return tree.propertyNames();
    }

    private <T> T roundTrip(T event, Class<T> type) {
        return json.readValue(json.writeValueAsString(event), type);
    }
}
