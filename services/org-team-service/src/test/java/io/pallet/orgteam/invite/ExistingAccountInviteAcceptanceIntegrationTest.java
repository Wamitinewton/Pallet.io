package io.pallet.orgteam.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.OrgInviteRejected;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.OrgTeamTestTokens;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
class ExistingAccountInviteAcceptanceIntegrationTest extends InviteIntegrationSupport {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String MY_ORGS = "/api/v1/org-team/orgs";

    @Autowired
    private PlatformEventPublisher publisher;

    @Test
    void aPersonalOrgFounderInvitedIntoATeamOrgGainsASecondMembershipAlongsideTheirOwn() throws Exception {
        String account = "user-" + UUID.randomUUID();
        String personalOrg = fixtures.newPersonalOrg(account);
        TestOrg teamOrg = newTeamOrg();

        acceptAs(account, inviteInto(teamOrg, emailOf(account), "VIEWER"), teamOrg, "viewer");

        assertThat(membershipsOf(account))
                .extracting(row -> row.get("org_id"), row -> row.get("role"), row -> row.get("status"))
                .containsExactlyInAnyOrder(
                        tuple(personalOrg, "OWNER", "ACTIVE"), tuple(teamOrg.orgId(), "VIEWER", "ACTIVE"));

        myOrgs(account)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].orgId").value(personalOrg))
                .andExpect(jsonPath("$.data.content[0].kind").value("PERSONAL"))
                .andExpect(jsonPath("$.data.content[0].myRole").value("OWNER"))
                .andExpect(jsonPath("$.data.content[1].orgId").value(teamOrg.orgId()))
                .andExpect(jsonPath("$.data.content[1].kind").value("TEAM"))
                .andExpect(jsonPath("$.data.content[1].myRole").value("VIEWER"));
    }

    @Test
    void anotherTeamOrgsOwnerInvitedWithALesserRoleKeepsTheirOwnershipThere() throws Exception {
        TestOrg ownOrg = newTeamOrg();
        String account = ownOrg.owner();
        TestOrg otherOrg = newTeamOrg();

        acceptAs(account, inviteInto(otherOrg, emailOf(account), "DEVELOPER"), otherOrg, "developer");

        assertThat(membershipsOf(account))
                .extracting(row -> row.get("org_id"), row -> row.get("role"), row -> row.get("status"))
                .containsExactlyInAnyOrder(
                        tuple(ownOrg.orgId(), "OWNER", "ACTIVE"), tuple(otherOrg.orgId(), "DEVELOPER", "ACTIVE"));
        assertThat(jdbc.queryForObject(
                        "SELECT owner_user_id FROM org_team.organizations WHERE org_id = ?",
                        String.class,
                        otherOrg.orgId()))
                .isEqualTo(otherOrg.owner());

        String body = myOrgs(account)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Map<String, Object>> listed = JsonPath.read(body, "$.data.content");
        assertThat(listed)
                .extracting(org -> org.get("orgId"), org -> org.get("kind"), org -> org.get("myRole"))
                .containsExactlyInAnyOrder(
                        tuple(ownOrg.orgId(), "TEAM", "OWNER"), tuple(otherOrg.orgId(), "TEAM", "DEVELOPER"));
    }

    private UUID inviteInto(TestOrg org, String email, String role) throws Exception {
        String id = JsonPath.read(
                invite(org.orgId(), org.owner(), email, role)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.data.id");
        return UUID.fromString(id);
    }

    private void acceptAs(String userId, UUID inviteId, TestOrg org, String claimedRole) {
        OrgInviteAccepted event = OrgInviteAccepted.of(
                org.orgId(), inviteId.toString(), userId, emailOf(userId), "Name " + userId, claimedRole);

        publisher.publish(event);

        await().atMost(WAIT).untilAsserted(() -> assertThat(statusOf(inviteId)).isEqualTo("ACCEPTED"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ? AND event_type = ?",
                        Integer.class,
                        org.orgId(),
                        OrgInviteRejected.TYPE))
                .isZero();
    }

    private List<Map<String, Object>> membershipsOf(String userId) {
        return jdbc.queryForList("SELECT org_id, role, status FROM org_team.memberships WHERE user_id = ?", userId);
    }

    private ResultActions myOrgs(String userId) throws Exception {
        return mvc.perform(get(MY_ORGS)
                .header(
                        "Authorization",
                        "Bearer " + OrgTeamTestTokens.forAccount(userId).signed()));
    }

    private static String emailOf(String userId) {
        return userId + "@example.com";
    }
}
