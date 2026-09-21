package io.pallet.orgteam.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
class MemberApiIntegrationTest extends MemberIntegrationSupport {

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void membersAreListedActiveOnlyInJoinOrderAndFilterable() throws Exception {
        TestOrg org = newTeamOrg();
        String viewer = fixtures.newMember(org.orgId(), "VIEWER", "ACTIVE");
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");
        fixtures.newMember(org.orgId(), "DEVELOPER", "REMOVED");

        perform(get(BASE, org.orgId()), org.orgId(), viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[*].status", everyItem(is("ACTIVE"))))
                .andExpect(jsonPath("$.data.content[0].version").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].removedBy").doesNotExist());
        perform(get(BASE, org.orgId()).param("role", "ADMIN"), org.orgId(), viewer)
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].userId").value(admin));
        perform(get(BASE, org.orgId()).param("q", admin.substring(0, 9)), org.orgId(), viewer)
                .andExpect(jsonPath("$.data.content[*].userId", contains(admin)));
        perform(get(BASE, org.orgId()).param("q", "%"), org.orgId(), viewer)
                .andExpect(jsonPath("$.data.totalElements").value(0));
        perform(get(BASE, org.orgId()).param("sort", "displayName,desc").param("size", "2"), org.orgId(), viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void sortingByRoleFollowsTheRoleHierarchyNotTheAlphabet() throws Exception {
        TestOrg org = newTeamOrg();
        String viewer = fixtures.newMember(org.orgId(), "VIEWER", "ACTIVE");
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");

        perform(get(BASE, org.orgId()).param("sort", "role,asc"), org.orgId(), viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].userId", contains(viewer, dev, admin, org.owner())));
        perform(get(BASE, org.orgId()).param("sort", "role,desc"), org.orgId(), viewer)
                .andExpect(jsonPath("$.data.content[*].userId", contains(org.owner(), admin, dev, viewer)));
    }

    @Test
    void anUnlistedSortFieldIsABadRequest() throws Exception {
        TestOrg org = newTeamOrg();

        perform(get(BASE, org.orgId()).param("sort", "email"), org.orgId(), org.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SORT"));
        perform(get(BASE, org.orgId()).param("sort", "version,desc"), org.orgId(), org.owner())
                .andExpect(status().isBadRequest());
    }

    @Test
    void removedMembersAreListedOnlyForAnAdminOrAbove() throws Exception {
        TestOrg org = newTeamOrg();
        String viewer = fixtures.newMember(org.orgId(), "VIEWER", "ACTIVE");
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");
        String gone = fixtures.newMember(org.orgId(), "DEVELOPER", "REMOVED");

        perform(get(BASE, org.orgId()).param("status", "REMOVED"), org.orgId(), viewer)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
        perform(get(BASE, org.orgId()).param("status", "REMOVED"), org.orgId(), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].userId", contains(gone)));
    }

    @Test
    void meReturnsTheCallersRowAndUserIdLookupsScopeToActiveMembers() throws Exception {
        TestOrg org = newTeamOrg();
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        String gone = fixtures.newMember(org.orgId(), "VIEWER", "REMOVED");

        perform(get(BASE + "/me", org.orgId()), org.orgId(), dev)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(dev))
                .andExpect(jsonPath("$.data.role").value("DEVELOPER"));
        perform(get(BASE + "/{userId}", org.orgId(), org.owner()), org.orgId(), dev)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("OWNER"));
        perform(get(BASE + "/{userId}", org.orgId(), gone), org.orgId(), dev)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MEMBER_NOT_FOUND"));
        perform(get(BASE + "/{userId}", org.orgId(), "nobody"), org.orgId(), dev)
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherOrgsMemberIsNotFound() throws Exception {
        TestOrg org = newTeamOrg();
        TestOrg other = newTeamOrg();

        perform(get(BASE + "/{userId}", org.orgId(), other.owner()), org.orgId(), org.owner())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MEMBER_NOT_FOUND"));
    }

    @Test
    void theOwnerChangesARoleAndTheChangeIsQueuedWithLowercaseNames() throws Exception {
        TestOrg org = newTeamOrg();
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        perform(roleChange(org.orgId(), dev, "ADMIN"), org.orgId(), org.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        assertThat(jdbc.queryForObject(
                        "SELECT role FROM org_team.memberships WHERE org_id = ? AND user_id = ?",
                        String.class,
                        org.orgId(),
                        dev))
                .isEqualTo("ADMIN");
        assertThat(events(org.orgId(), "org.member.role.changed"))
                .singleElement()
                .satisfies(event -> assertThat(event)
                        .containsEntry("user_id", dev)
                        .containsEntry("previous_role", "developer")
                        .containsEntry("new_role", "admin"));
        assertThat(events(org.orgId(), "audit.event.recorded"))
                .singleElement()
                .satisfies(event -> assertThat(event).containsEntry("action", "member.role_changed"));
    }

    @Test
    void changingToTheCurrentRoleIsOkWithoutAnEvent() throws Exception {
        TestOrg org = newTeamOrg();
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        perform(roleChange(org.orgId(), dev, "DEVELOPER"), org.orgId(), org.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("DEVELOPER"));

        assertThat(outboxTypes(org.orgId())).isEmpty();
    }

    @Test
    void anAdminCannotChangeARole() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        perform(roleChange(org.orgId(), dev, "VIEWER"), org.orgId(), admin)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));

        assertThat(outboxTypes(org.orgId())).isEmpty();
    }

    @Test
    void theOwnerRoleCanNeitherBeGrantedNorChanged() throws Exception {
        TestOrg org = newTeamOrg();
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        perform(roleChange(org.orgId(), dev, "OWNER"), org.orgId(), org.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_ROLE_TRANSITION"));
        perform(roleChange(org.orgId(), org.owner(), "ADMIN"), org.orgId(), org.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_ROLE_TRANSITION"));

        assertThat(activeOwners(org.orgId())).isEqualTo(1);
        assertThat(outboxTypes(org.orgId())).isEmpty();
    }

    @Test
    void patchRejectsUnknownFields() throws Exception {
        TestOrg org = newTeamOrg();
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        perform(
                        patch(BASE + "/{userId}", org.orgId(), dev)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"ADMIN\",\"email\":\"x@example.com\"}"),
                        org.orgId(),
                        org.owner())
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAdminRemovesADeveloperAndTheirAccessEndsImmediately() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        insertTeamAssignment(org.orgId(), dev);

        perform(get(BASE + "/me", org.orgId()), org.orgId(), dev).andExpect(status().isOk());
        perform(delete(BASE + "/{userId}", org.orgId(), dev), org.orgId(), admin)
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForMap(
                        "SELECT status, removed_by FROM org_team.memberships WHERE org_id = ? AND user_id = ?",
                        org.orgId(),
                        dev))
                .containsEntry("status", "REMOVED")
                .containsEntry("removed_by", admin);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.team_members WHERE org_id = ? AND user_id = ?",
                        Integer.class,
                        org.orgId(),
                        dev))
                .isZero();
        assertThat(events(org.orgId(), "org.member.removed"))
                .singleElement()
                .satisfies(event ->
                        assertThat(event).containsEntry("user_id", dev).containsEntry("email", dev + "@example.com"));
        assertThat(events(org.orgId(), "audit.event.recorded"))
                .singleElement()
                .satisfies(event -> assertThat(event).containsEntry("action", "member.removed"));
        perform(get(BASE + "/me", org.orgId()), org.orgId(), dev)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_A_MEMBER"));
        perform(delete(BASE + "/{userId}", org.orgId(), dev), org.orgId(), admin)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MEMBER_NOT_FOUND"));
    }

    @Test
    void aMemberMayLeave() throws Exception {
        TestOrg org = newTeamOrg();
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        perform(delete(BASE + "/{userId}", org.orgId(), dev), org.orgId(), dev).andExpect(status().isNoContent());

        assertThat(events(org.orgId(), "audit.event.recorded"))
                .singleElement()
                .satisfies(event -> assertThat(event).containsEntry("action", "member.left"));
    }

    @Test
    void theOwnerCannotLeaveOrBeRemoved() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");

        perform(delete(BASE + "/{userId}", org.orgId(), org.owner()), org.orgId(), org.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LAST_OWNER"));
        perform(delete(BASE + "/{userId}", org.orgId(), org.owner()), org.orgId(), admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LAST_OWNER"));

        assertThat(activeOwners(org.orgId())).isEqualTo(1);
        assertThat(outboxTypes(org.orgId())).isEmpty();
    }

    @Test
    void anAdminCannotRemoveAnotherAdmin() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");
        String otherAdmin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");

        perform(delete(BASE + "/{userId}", org.orgId(), otherAdmin), org.orgId(), admin)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
    }

    @Test
    void ownersRemoveAnyoneButThemselves() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");

        perform(delete(BASE + "/{userId}", org.orgId(), admin), org.orgId(), org.owner())
                .andExpect(status().isNoContent());
    }

    @Test
    void transferNeedsAFreshAuthentication() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");

        mvc.perform(post(BASE + "/{userId}/transfer-ownership", org.orgId(), admin)
                        .header("Authorization", staleBearer(org.orgId(), org.owner())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REAUTHENTICATION_REQUIRED"));

        assertThat(activeOwners(org.orgId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT owner_user_id FROM org_team.organizations WHERE org_id = ?", String.class, org.orgId()))
                .isEqualTo(org.owner());
    }

    @Test
    void transferPromotesTheTargetDemotesTheOwnerAndQueuesPromoteBeforeDemote() throws Exception {
        TestOrg org = newTeamOrg();
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        perform(post(BASE + "/{userId}/transfer-ownership", org.orgId(), dev), org.orgId(), org.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(dev))
                .andExpect(jsonPath("$.data.role").value("OWNER"));

        assertThat(role(org.orgId(), dev)).isEqualTo("OWNER");
        assertThat(role(org.orgId(), org.owner())).isEqualTo("ADMIN");
        assertThat(activeOwners(org.orgId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT owner_user_id FROM org_team.organizations WHERE org_id = ?", String.class, org.orgId()))
                .isEqualTo(dev);

        List<Map<String, Object>> changes = events(org.orgId(), "org.member.role.changed");
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0))
                .containsEntry("user_id", dev)
                .containsEntry("previous_role", "developer")
                .containsEntry("new_role", "owner");
        assertThat(changes.get(1))
                .containsEntry("user_id", org.owner())
                .containsEntry("previous_role", "owner")
                .containsEntry("new_role", "admin");
        assertThat(events(org.orgId(), "audit.event.recorded"))
                .singleElement()
                .satisfies(event -> assertThat(event).containsEntry("action", "org.ownership_transferred"));

        perform(get(BASE + "/me", org.orgId()), org.orgId(), org.owner())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
        perform(post(BASE + "/{userId}/transfer-ownership", org.orgId(), org.owner()), org.orgId(), org.owner())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
    }

    @Test
    void transferToSelfOrToARemovedMemberFails() throws Exception {
        TestOrg org = newTeamOrg();
        String gone = fixtures.newMember(org.orgId(), "ADMIN", "REMOVED");

        perform(post(BASE + "/{userId}/transfer-ownership", org.orgId(), org.owner()), org.orgId(), org.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_ROLE_TRANSITION"));
        perform(post(BASE + "/{userId}/transfer-ownership", org.orgId(), gone), org.orgId(), org.owner())
                .andExpect(status().isNotFound());

        assertThat(activeOwners(org.orgId())).isEqualTo(1);
        assertThat(outboxTypes(org.orgId())).isEmpty();
    }

    @Test
    void theDatabaseRefusesASecondActiveOwnerEvenWhenTheServiceLogicIsBypassed() {
        TestOrg org = newTeamOrg();
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
                    Membership member =
                            memberships.findByOrgIdAndUserId(org.orgId(), dev).orElseThrow();
                    member.changeRole(Role.OWNER);
                    memberships.saveAndFlush(member);
                }))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_memberships_one_active_owner");

        assertThat(activeOwners(org.orgId())).isEqualTo(1);
    }

    private String role(String orgId, String userId) {
        return jdbc.queryForObject(
                "SELECT role FROM org_team.memberships WHERE org_id = ? AND user_id = ?", String.class, orgId, userId);
    }

    private static MockHttpServletRequestBuilder roleChange(String orgId, String userId, String role) {
        return patch(BASE + "/{userId}", orgId, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\"}");
    }
}
