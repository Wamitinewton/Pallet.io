package io.pallet.orgteam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.OrgInviteRejected;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.outbox.TopicProbe;
import io.pallet.orgteam.outbox.TopicProbe.Received;
import io.pallet.orgteam.security.OrgTeamTestTokens;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the service through the same events and HTTP calls the rest of the platform uses, with a
 * scratch producer and probe standing in for identity-service and notification-service. Requests
 * carry locally signed tokens: the Keycloak side of the handoff is proven in identity-service's
 * own tests.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
@TestPropertySource(properties = "pallet.orgteam.outbox.enabled=true")
class EndToEndCrossServiceIntegrationTest {

    private static final String API = "/api/v1/org-team";
    private static final String ORG = API + "/orgs/{orgId}";
    private static final String MEMBERS = ORG + "/members";
    private static final String INVITES = ORG + "/invites";
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private PlatformEventPublisher publisher;

    private final List<Org> orgs = new ArrayList<>();

    private record Org(String orgId, String slug, String owner) {}

    @AfterEach
    void assertInvariantsHoldAndClean() {
        try {
            assertInvariants();
        } finally {
            orgs.forEach(org -> {
                jdbc.update("DELETE FROM org_team.team_members WHERE org_id = ?", org.orgId());
                jdbc.update("DELETE FROM org_team.apps WHERE org_id = ?", org.orgId());
                jdbc.update("DELETE FROM org_team.teams WHERE org_id = ?", org.orgId());
                jdbc.update("DELETE FROM org_team.invites WHERE org_id = ?", org.orgId());
                jdbc.update("DELETE FROM org_team.memberships WHERE org_id = ?", org.orgId());
                jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", org.orgId());
                jdbc.update("DELETE FROM org_team.organizations WHERE org_id = ?", org.orgId());
            });
            orgs.clear();
        }
    }

    @Test
    void signUpProvisionsTheOrgAndTheOwnerIsAnnouncedExactlyOnce() throws Exception {
        try (TopicProbe probe = probe(Topics.ORG_MEMBER_ADDED)) {
            Org org = provision();

            mvc.perform(as(get(ORG, org.orgId()), org, org.owner()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.slug").value(org.slug()));
            List<Received> added = probe.awaitCount(org.orgId(), 1);
            assertThat(added).hasSize(1);
            assertThat(added.getFirst().body().get("userId").asString()).isEqualTo(org.owner());
        }
    }

    @Test
    void anInviteIsEmailedPreviewedAcceptedAndTheInviteeBecomesAMember() throws Exception {
        Org org = provision();
        String email = newEmail();
        String invitee = newUser();

        try (TopicProbe notifications = probe(Topics.NOTIFICATION_REQUESTED);
                TopicProbe memberAdded = probe(Topics.ORG_MEMBER_ADDED)) {
            UUID inviteId = createInvite(org, email, "DEVELOPER");

            Received request = inviteNotification(notifications, org, email);
            String acceptUrl = request.body().get("variables").get("acceptUrl").asString();
            assertThat(request.body().get("notificationType").asString()).isEqualTo("ORG_INVITE");

            String preview = mvc.perform(get(API + "/invites/{token}", tokenOf(acceptUrl)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.orgName").value("Org " + org.slug()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(preview).doesNotContain(email);

            acceptAtIdentity(org, inviteId, invitee, email);

            awaitMember(org, invitee);
            mvc.perform(as(get(MEMBERS + "/me", org.orgId()), org, invitee))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.role").value("DEVELOPER"));
            assertThat(memberAdded.awaitCount(org.orgId(), 2))
                    .extracting(received -> received.body().get("userId").asString())
                    .contains(org.owner(), invitee);
        }
        assertThat(inviteStatus(org, email)).isEqualTo("ACCEPTED");
    }

    @Test
    void aRoleChangeBitesImmediatelyAndARemovalCutsAccessAtOnce() throws Exception {
        Org org = provision();
        String member = joinAs(org, "DEVELOPER");

        try (TopicProbe roleChanged = probe(Topics.ORG_MEMBER_ROLE_CHANGED);
                TopicProbe removed = probe(Topics.ORG_MEMBER_REMOVED)) {
            mvc.perform(as(post(INVITES, org.orgId()), org, member)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(inviteBody(newEmail(), "VIEWER")))
                    .andExpect(status().isForbidden());

            mvc.perform(as(patch(MEMBERS + "/{userId}", org.orgId(), member), org, org.owner())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isOk());

            mvc.perform(as(post(INVITES, org.orgId()), org, member)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(inviteBody(newEmail(), "VIEWER")))
                    .andExpect(status().isCreated());
            Received change = roleChanged.awaitCount(org.orgId(), 1).getFirst();
            assertThat(change.body().get("previousRole").asString()).isEqualTo("developer");
            assertThat(change.body().get("newRole").asString()).isEqualTo("admin");

            mvc.perform(as(delete(MEMBERS + "/{userId}", org.orgId(), member), org, org.owner()))
                    .andExpect(status().isNoContent());

            mvc.perform(as(get(MEMBERS + "/me", org.orgId()), org, member))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("NOT_A_MEMBER"));
            assertThat(removed.awaitCount(org.orgId(), 1)
                            .getFirst()
                            .body()
                            .get("userId")
                            .asString())
                    .isEqualTo(member);
        }
    }

    @Test
    void aRevokedInviteAcceptedAnywayIsRejectedAndNoMembershipExists() throws Exception {
        Org org = provision();
        String email = newEmail();
        String invitee = newUser();
        UUID inviteId = createInvite(org, email, "VIEWER");
        mvc.perform(as(delete(INVITES + "/{inviteId}", org.orgId(), inviteId), org, org.owner()))
                .andExpect(status().isNoContent());

        try (TopicProbe rejected = probe(Topics.ORG_INVITE_REJECTED)) {
            acceptAtIdentity(org, inviteId, invitee, email);

            assertRejected(rejected.awaitCount(org.orgId(), 1), inviteId, invitee, OrgInviteRejected.REASON_REVOKED);
        }
        assertThat(membershipCount(org, invitee)).isZero();
    }

    @Test
    void anExpiredInviteAcceptedAnywayIsRejectedAsExpired() throws Exception {
        Org org = provision();
        String email = newEmail();
        String invitee = newUser();
        UUID inviteId = createInvite(org, email, "VIEWER");
        jdbc.update("UPDATE org_team.invites SET expires_at = now() - interval '1 hour' WHERE id = ?", inviteId);

        try (TopicProbe rejected = probe(Topics.ORG_INVITE_REJECTED)) {
            acceptAtIdentity(org, inviteId, invitee, email);

            assertRejected(rejected.awaitCount(org.orgId(), 1), inviteId, invitee, OrgInviteRejected.REASON_EXPIRED);
        }
        assertThat(membershipCount(org, invitee)).isZero();
    }

    @Test
    void ownershipTransferNeedsFreshAuthAndEmitsPromoteBeforeDemote() throws Exception {
        Org org = provision();
        String successor = joinAs(org, "ADMIN");
        String staleToken = OrgTeamTestTokens.forMember(org.orgId(), org.owner())
                .authenticatedAt(Instant.now().minus(Duration.ofHours(2)))
                .signed();

        try (TopicProbe roleChanged = probe(Topics.ORG_MEMBER_ROLE_CHANGED)) {
            mvc.perform(post(MEMBERS + "/{userId}/transfer-ownership", org.orgId(), successor)
                            .header("Authorization", "Bearer " + staleToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("REAUTHENTICATION_REQUIRED"));

            mvc.perform(as(post(MEMBERS + "/{userId}/transfer-ownership", org.orgId(), successor), org, org.owner()))
                    .andExpect(status().is2xxSuccessful());

            List<Received> changes = roleChanged.awaitCount(org.orgId(), 2);
            assertThat(changes).hasSize(2);
            assertThat(changes.get(0).body().get("userId").asString()).isEqualTo(successor);
            assertThat(changes.get(0).body().get("newRole").asString()).isEqualTo("owner");
            assertThat(changes.get(1).body().get("userId").asString()).isEqualTo(org.owner());
            assertThat(changes.get(1).body().get("newRole").asString()).isEqualTo("admin");
        }
        mvc.perform(as(get(MEMBERS + "/me", org.orgId()), org, org.owner()))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    void deletingTheOrgPublishesAppDeletedBeforeOrgDeletedAndRejectsLaterAccepts() throws Exception {
        Org org = provision();
        String email = newEmail();
        String invitee = newUser();
        UUID pendingInvite = createInvite(org, email, "VIEWER");
        mvc.perform(as(post(ORG + "/apps", org.orgId()), org, org.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Web\",\"cloudProvider\":\"AWS\",\"region\":\"us-east-1\"}"))
                .andExpect(status().isCreated());

        try (TopicProbe lifecycle = probe(Topics.APP_DELETED, Topics.ORG_DELETED);
                TopicProbe rejected = probe(Topics.ORG_INVITE_REJECTED)) {
            mvc.perform(as(delete(ORG, org.orgId()), org, org.owner()).header("X-Confirm-Slug", org.slug()))
                    .andExpect(status().isNoContent());

            assertThat(lifecycle.awaitCount(org.orgId(), 2))
                    .extracting(Received::topic)
                    .containsExactlyInAnyOrder(Topics.APP_DELETED, Topics.ORG_DELETED);
            assertThat(outboxTypes(org))
                    .containsSubsequence(Topics.APP_DELETED, Topics.ORG_DELETED)
                    .endsWith(Topics.ORG_DELETED, "audit.event.recorded");

            mvc.perform(as(get(ORG, org.orgId()), org, org.owner()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("ORG_NOT_FOUND"));

            acceptAtIdentity(org, pendingInvite, invitee, email);
            assertRejected(
                    rejected.awaitCount(org.orgId(), 1), pendingInvite, invitee, OrgInviteRejected.REASON_ORG_DELETED);
        }
        assertThat(membershipCount(org, invitee)).isZero();
    }

    private Org provision() throws Exception {
        String orgId = "org-" + UUID.randomUUID();
        Org org = new Org(orgId, "acme-" + orgId.substring(4, 12), newUser());
        orgs.add(org);
        publisher.publish(OrgProvisioned.of(
                orgId, "Org " + org.slug(), org.slug(), org.owner(), org.owner() + "@example.com", "Owner"));
        await().atMost(WAIT)
                .ignoreExceptions()
                .untilAsserted(
                        () -> mvc.perform(as(get(ORG, orgId), org, org.owner())).andExpect(status().isOk()));
        return org;
    }

    private String joinAs(Org org, String role) throws Exception {
        String email = newEmail();
        String userId = newUser();
        UUID inviteId = createInvite(org, email, role);
        acceptAtIdentity(org, inviteId, userId, email);
        awaitMember(org, userId);
        return userId;
    }

    private UUID createInvite(Org org, String email, String role) throws Exception {
        String body = mvc.perform(as(post(INVITES, org.orgId()), org, org.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, role)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(jsonMapper.readTree(body).get("data").get("id").asString());
    }

    private void acceptAtIdentity(Org org, UUID inviteId, String userId, String email) {
        publisher.publish(new OrgInviteAccepted(
                UUID.randomUUID(),
                OrgInviteAccepted.TYPE,
                org.orgId(),
                Instant.now(),
                inviteId.toString(),
                userId,
                email,
                "Invitee",
                "viewer"));
    }

    private void awaitMember(Org org, String userId) {
        await().atMost(WAIT)
                .ignoreExceptions()
                .untilAsserted(() -> mvc.perform(as(get(MEMBERS + "/me", org.orgId()), org, userId))
                        .andExpect(status().isOk()));
    }

    private Received inviteNotification(TopicProbe probe, Org org, String email) {
        return probe.awaitCount(org.orgId(), 1).stream()
                .filter(received ->
                        email.equals(received.body().get("recipient").asString()))
                .findFirst()
                .orElseThrow();
    }

    private static void assertRejected(List<Received> rejected, UUID inviteId, String userId, String reason) {
        assertThat(rejected).hasSize(1);
        JsonNode body = rejected.getFirst().body();
        assertThat(body.get("reason").asString()).isEqualTo(reason);
        assertThat(body.get("inviteId").asString()).isEqualTo(inviteId.toString());
        assertThat(body.get("userId").asString()).isEqualTo(userId);
    }

    private void assertInvariants() {
        List<String> orgIds = orgs.stream().map(Org::orgId).toList();
        if (orgIds.isEmpty()) {
            return;
        }
        String in =
                "(" + String.join(",", orgIds.stream().map(id -> "'" + id + "'").toList()) + ")";

        assertThat(jdbc.queryForList("""
                        SELECT org_id FROM org_team.memberships
                        WHERE role = 'OWNER' AND status = 'ACTIVE' AND org_id IN %s
                        GROUP BY org_id HAVING count(*) <> 1
                        """.formatted(in), String.class))
                .as("active orgs with anything other than one active owner")
                .isEmpty();
        assertThat(jdbc.queryForList("""
                        SELECT o.org_id FROM org_team.organizations o
                        LEFT JOIN org_team.memberships m
                          ON m.org_id = o.org_id AND m.user_id = o.owner_user_id
                         AND m.role = 'OWNER' AND m.status = 'ACTIVE'
                        WHERE o.status = 'ACTIVE' AND o.org_id IN %s AND m.user_id IS NULL
                        """.formatted(in), String.class))
                .as("active orgs whose owner_user_id has no matching owner membership")
                .isEmpty();
        assertThat(jdbc.queryForList("""
                        SELECT tm.user_id FROM org_team.team_members tm
                        JOIN org_team.memberships m USING (org_id, user_id)
                        WHERE m.status <> 'ACTIVE' AND tm.org_id IN %s
                        """.formatted(in), String.class))
                .as("team members who are not active members")
                .isEmpty();
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM org_team.outbox_events WHERE status <> 'PUBLISHED'"
                                        + " AND org_id IN " + in,
                                Integer.class))
                        .as("outbox rows not yet published")
                        .isZero());
    }

    private List<String> outboxTypes(Org org) {
        return jdbc.queryForList(
                "SELECT event_type FROM org_team.outbox_events WHERE org_id = ? ORDER BY id",
                String.class,
                org.orgId());
    }

    private int membershipCount(Org org, String userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.memberships WHERE org_id = ? AND user_id = ?",
                Integer.class,
                org.orgId(),
                userId);
    }

    private String inviteStatus(Org org, String email) {
        return jdbc.queryForObject(
                "SELECT status FROM org_team.invites WHERE org_id = ? AND lower(email) = lower(?)",
                String.class,
                org.orgId(),
                email);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Org org, String userId) {
        return request.header(
                "Authorization",
                "Bearer " + OrgTeamTestTokens.forMember(org.orgId(), userId).signed());
    }

    private TopicProbe probe(String... topics) {
        return new TopicProbe(kafka.getBootstrapServers(), jsonMapper, topics);
    }

    private static String tokenOf(String acceptUrl) {
        return acceptUrl.substring(acceptUrl.lastIndexOf('/') + 1);
    }

    private static String inviteBody(String email, String role) {
        return "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
    }

    private static String newUser() {
        return "user-" + UUID.randomUUID();
    }

    private static String newEmail() {
        return "invitee-" + UUID.randomUUID() + "@example.com";
    }
}
