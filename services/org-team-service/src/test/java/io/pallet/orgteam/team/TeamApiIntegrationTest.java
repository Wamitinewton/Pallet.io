package io.pallet.orgteam.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
class TeamApiIntegrationTest extends TeamIntegrationSupport {

    @Test
    void aTeamGoesThroughItsWholeLifecycle() throws Exception {
        TestOrg org = newTeamOrg();
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        String viewer = fixtures.newMember(org.orgId(), "VIEWER", "ACTIVE");

        String created = send(post(TEAMS, org.orgId()), "{\"name\":\"Platform Team\"}", org.orgId(), org.owner())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").value("platform-team"))
                .andExpect(jsonPath("$.data.memberCount").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String teamId = JsonPath.read(created, "$.data.id");

        for (String member : List.of(dev, viewer)) {
            send(
                            post(TEAMS + "/{teamId}/members", org.orgId(), teamId),
                            "{\"userId\":\"" + member + "\"}",
                            org.orgId(),
                            org.owner())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.userId").value(member));
        }

        perform(get(TEAMS + "/{teamId}", org.orgId(), teamId), org.orgId(), viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberCount").value(2));
        perform(get(TEAMS + "/{teamId}/members", org.orgId(), teamId), org.orgId(), viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].version").doesNotExist());

        send(patch(TEAMS + "/{teamId}", org.orgId(), teamId), "{\"name\":\"Infra\"}", org.orgId(), org.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Infra"))
                .andExpect(jsonPath("$.data.slug").value("platform-team"))
                .andExpect(jsonPath("$.data.memberCount").value(2));

        perform(delete(TEAMS + "/{teamId}/members/{userId}", org.orgId(), teamId, dev), org.orgId(), org.owner())
                .andExpect(status().isNoContent());
        perform(get(TEAMS + "/{teamId}", org.orgId(), teamId), org.orgId(), viewer)
                .andExpect(jsonPath("$.data.memberCount").value(1));
        perform(delete(TEAMS + "/{teamId}/members/{userId}", org.orgId(), teamId, dev), org.orgId(), org.owner())
                .andExpect(status().isNotFound());

        perform(delete(TEAMS + "/{teamId}", org.orgId(), teamId), org.orgId(), org.owner())
                .andExpect(status().isNoContent());
        perform(get(TEAMS + "/{teamId}", org.orgId(), teamId), org.orgId(), viewer)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TEAM_NOT_FOUND"));

        assertThat(auditActions(org.orgId()))
                .containsExactly(
                        "team.created",
                        "team.member_added",
                        "team.member_added",
                        "team.renamed",
                        "team.member_removed",
                        "team.deleted");
    }

    @Test
    void listIsPagedSortedByNameWithGroupedCountsAndAWhitelistedSort() throws Exception {
        TestOrg org = newTeamOrg();
        UUID beta = seedTeam(org.orgId(), "beta");
        seedTeam(org.orgId(), "alpha");
        UUID gamma = seedTeam(org.orgId(), "gamma");
        seedAssignment(org.orgId(), beta, org.owner());
        seedAssignment(org.orgId(), gamma, org.owner());
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        seedAssignment(org.orgId(), gamma, dev);

        perform(get(TEAMS, org.orgId()), org.orgId(), dev)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].slug", contains("alpha", "beta", "gamma")))
                .andExpect(jsonPath("$.data.content[*].memberCount", contains(0, 1, 2)))
                .andExpect(jsonPath("$.data.totalElements").value(3));
        perform(get(TEAMS, org.orgId()).param("sort", "name,desc").param("size", "2"), org.orgId(), dev)
                .andExpect(jsonPath("$.data.content[*].slug", contains("gamma", "beta")))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        perform(get(TEAMS, org.orgId()).param("sort", "slug"), org.orgId(), dev)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SORT"));
        perform(get(TEAMS, org.orgId()).param("sort", "version"), org.orgId(), dev)
                .andExpect(status().isBadRequest());
    }

    @Test
    void writesNeedAnAdminAndReadsNeedOnlyMembership() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        String viewer = fixtures.newMember(org.orgId(), "VIEWER", "ACTIVE");
        UUID team = seedTeam(org.orgId(), "platform");

        for (String caller : List.of(dev, viewer)) {
            send(post(TEAMS, org.orgId()), "{\"name\":\"Nope\"}", org.orgId(), caller)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
            send(patch(TEAMS + "/{id}", org.orgId(), team), "{\"name\":\"Nope\"}", org.orgId(), caller)
                    .andExpect(status().isForbidden());
            perform(delete(TEAMS + "/{id}", org.orgId(), team), org.orgId(), caller)
                    .andExpect(status().isForbidden());
            send(
                            post(TEAMS + "/{id}/members", org.orgId(), team),
                            "{\"userId\":\"" + caller + "\"}",
                            org.orgId(),
                            caller)
                    .andExpect(status().isForbidden());
            perform(get(TEAMS, org.orgId()), org.orgId(), caller).andExpect(status().isOk());
            perform(get(TEAMS + "/{id}/members", org.orgId(), team), org.orgId(), caller)
                    .andExpect(status().isOk());
        }
        send(post(TEAMS, org.orgId()), "{\"name\":\"Allowed\"}", org.orgId(), admin)
                .andExpect(status().isCreated());
    }

    @Test
    void aDemotedOrRemovedAdminLosesTeamWritesImmediately() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");

        fixtures.setRole(org.orgId(), admin, "VIEWER");
        send(post(TEAMS, org.orgId()), "{\"name\":\"Nope\"}", org.orgId(), admin)
                .andExpect(status().isForbidden());

        fixtures.setStatus(org.orgId(), admin, "REMOVED");
        send(post(TEAMS, org.orgId()), "{\"name\":\"Nope\"}", org.orgId(), admin)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_A_MEMBER"));
    }

    @Test
    void aTakenSlugIsAConflictExplicitOrDerived() throws Exception {
        TestOrg org = newTeamOrg();
        seedTeam(org.orgId(), "platform");

        send(post(TEAMS, org.orgId()), "{\"name\":\"Anything\",\"slug\":\"platform\"}", org.orgId(), org.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLUG_TAKEN"));
        send(post(TEAMS, org.orgId()), "{\"name\":\"Platform\"}", org.orgId(), org.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLUG_TAKEN"));
    }

    @Test
    void twoOrgsMayUseTheSameSlug() throws Exception {
        TestOrg first = newTeamOrg();
        TestOrg second = newTeamOrg();

        send(post(TEAMS, first.orgId()), "{\"name\":\"Platform\"}", first.orgId(), first.owner())
                .andExpect(status().isCreated());
        send(post(TEAMS, second.orgId()), "{\"name\":\"Platform\"}", second.orgId(), second.owner())
                .andExpect(status().isCreated());
    }

    @Test
    void twentyConcurrentCreatesOfOneSlugYieldExactlyOneTeam() throws Exception {
        TestOrg org = newTeamOrg();

        List<Callable<Integer>> creates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            creates.add(() ->
                    statusOf(send(post(TEAMS, org.orgId()), "{\"name\":\"Platform\"}", org.orgId(), org.owner())));
        }
        List<Integer> statuses = raceAll(creates);

        assertThat(statuses.stream().filter(code -> code == 201)).hasSize(1);
        assertThat(statuses.stream().filter(code -> code == 409)).hasSize(19);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.teams WHERE org_id = ? AND slug = 'platform'",
                        Integer.class,
                        org.orgId()))
                .isEqualTo(1);
    }

    @Test
    void theTeamQuotaIsEnforcedExactlyEvenUnderConcurrency() throws Exception {
        TestOrg org = newTeamOrg();
        for (int i = 0; i < 98; i++) {
            seedTeam(org.orgId(), "seed-" + i);
        }

        List<Callable<Integer>> creates = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String body = "{\"name\":\"extra " + i + "\"}";
            creates.add(() -> statusOf(send(post(TEAMS, org.orgId()), body, org.orgId(), org.owner())));
        }
        List<Integer> statuses = raceAll(creates);

        assertThat(statuses.stream().filter(code -> code == 201)).hasSize(2);
        assertThat(statuses.stream().filter(code -> code == 409)).hasSize(4);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.teams WHERE org_id = ?", Integer.class, org.orgId()))
                .isEqualTo(100);
        send(post(TEAMS, org.orgId()), "{\"name\":\"one too many\"}", org.orgId(), org.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("QUOTA_EXCEEDED"));
    }

    @Test
    void anotherOrgsTeamIsNeverVisibleOrWritable() throws Exception {
        TestOrg mine = newTeamOrg();
        TestOrg theirs = newTeamOrg();
        UUID theirTeam = seedTeam(theirs.orgId(), "secret");

        perform(get(TEAMS + "/{id}", mine.orgId(), theirTeam), mine.orgId(), mine.owner())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TEAM_NOT_FOUND"));
        send(patch(TEAMS + "/{id}", mine.orgId(), theirTeam), "{\"name\":\"Mine now\"}", mine.orgId(), mine.owner())
                .andExpect(status().isNotFound());
        perform(delete(TEAMS + "/{id}", mine.orgId(), theirTeam), mine.orgId(), mine.owner())
                .andExpect(status().isNotFound());
        perform(get(TEAMS + "/{id}/members", mine.orgId(), theirTeam), mine.orgId(), mine.owner())
                .andExpect(status().isNotFound());
        perform(get(TEAMS, theirs.orgId()), mine.orgId(), mine.owner())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORG_NOT_FOUND"));

        assertThat(jdbc.queryForObject("SELECT name FROM org_team.teams WHERE id = ?", String.class, theirTeam))
                .isEqualTo("secret");
    }

    @Test
    void theSlugCannotBeChangedThroughPatch() throws Exception {
        TestOrg org = newTeamOrg();
        UUID team = seedTeam(org.orgId(), "platform");

        send(
                        patch(TEAMS + "/{id}", org.orgId(), team),
                        "{\"name\":\"Infra\",\"slug\":\"infra\"}",
                        org.orgId(),
                        org.owner())
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT slug FROM org_team.teams WHERE id = ?", String.class, team))
                .isEqualTo("platform");
    }

    @Test
    void anActiveOrgMemberCanBeAddedOnceAndOnlyOnce() throws Exception {
        TestOrg org = newTeamOrg();
        UUID team = seedTeam(org.orgId(), "platform");
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        String body = "{\"userId\":\"" + dev + "\"}";

        send(post(TEAMS + "/{id}/members", org.orgId(), team), body, org.orgId(), org.owner())
                .andExpect(status().isCreated());
        send(post(TEAMS + "/{id}/members", org.orgId(), team), body, org.orgId(), org.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ALREADY_IN_TEAM"));

        assertThat(assignments(org.orgId(), dev)).isEqualTo(1);
    }

    @Test
    void aRemovedOrUnknownUserCannotBeAddedToATeam() throws Exception {
        TestOrg org = newTeamOrg();
        UUID team = seedTeam(org.orgId(), "platform");
        String gone = fixtures.newMember(org.orgId(), "DEVELOPER", "REMOVED");

        for (String userId : List.of(gone, "nobody")) {
            send(
                            post(TEAMS + "/{id}/members", org.orgId(), team),
                            "{\"userId\":\"" + userId + "\"}",
                            org.orgId(),
                            org.owner())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("MEMBER_NOT_FOUND"));
        }
        assertThat(assignments(org.orgId(), gone)).isZero();
    }

    @Test
    void aUserOfAnotherOrgIsRejectedByTheApiAndByTheDatabase() throws Exception {
        TestOrg mine = newTeamOrg();
        TestOrg theirs = newTeamOrg();
        UUID team = seedTeam(mine.orgId(), "platform");
        UUID theirTeam = seedTeam(theirs.orgId(), "theirs");

        send(
                        post(TEAMS + "/{id}/members", mine.orgId(), team),
                        "{\"userId\":\"" + theirs.owner() + "\"}",
                        mine.orgId(),
                        mine.owner())
                .andExpect(status().isNotFound());

        assertThatThrownBy(() -> seedAssignment(mine.orgId(), team, theirs.owner()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO org_team.team_members (team_id, user_id, org_id, added_by) VALUES (?, ?, ?, 'seed')",
                        team,
                        theirs.owner(),
                        theirs.orgId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> seedAssignment(mine.orgId(), theirTeam, mine.owner()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(assignments(theirs.orgId(), theirs.owner())).isZero();
    }

    @Test
    void deletingATeamCascadesInTheSchemaAndDetachesItsApps() throws Exception {
        TestOrg org = newTeamOrg();
        UUID team = seedTeam(org.orgId(), "platform");
        UUID survivor = seedTeam(org.orgId(), "other");
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        seedAssignment(org.orgId(), team, dev);
        seedAssignment(org.orgId(), survivor, dev);
        UUID app = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO org_team.apps (id, org_id, team_id, name, slug, cloud_provider, region, status)
                        VALUES (?, ?, ?, 'web', 'web', 'AWS', 'us-east-1', 'ACTIVE')
                        """, app, org.orgId(), team);

        perform(delete(TEAMS + "/{id}", org.orgId(), team), org.orgId(), org.owner())
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.team_members WHERE team_id = ?", Integer.class, team))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.team_members WHERE team_id = ?", Integer.class, survivor))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT org_id, team_id, status FROM org_team.apps WHERE id = ?", app))
                .containsEntry("org_id", org.orgId())
                .containsEntry("team_id", null)
                .containsEntry("status", "ACTIVE");
    }

    @Test
    void removingAMemberFromTheOrgDropsThemFromTheirTeamsAndTheTeamListing() throws Exception {
        TestOrg org = newTeamOrg();
        UUID team = seedTeam(org.orgId(), "platform");
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        seedAssignment(org.orgId(), team, dev);

        perform(delete(MEMBERS + "/{userId}", org.orgId(), dev), org.orgId(), org.owner())
                .andExpect(status().isNoContent());

        perform(get(TEAMS + "/{id}/members", org.orgId(), team), org.orgId(), org.owner())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        perform(get(TEAMS + "/{id}", org.orgId(), team), org.orgId(), org.owner())
                .andExpect(jsonPath("$.data.memberCount").value(0));
    }

    @RepeatedTest(10)
    void aMemberRemovalRacingATeamAddNeverLeavesARemovedMemberOnATeam() throws Exception {
        TestOrg org = newTeamOrg();
        UUID team = seedTeam(org.orgId(), "platform");
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        List<Integer> statuses = race(
                () -> statusOf(perform(delete(MEMBERS + "/{userId}", org.orgId(), dev), org.orgId(), org.owner())),
                () -> statusOf(send(
                        post(TEAMS + "/{id}/members", org.orgId(), team),
                        "{\"userId\":\"" + dev + "\"}",
                        org.orgId(),
                        org.owner())));

        assertThat(statuses.get(0)).isEqualTo(204);
        assertThat(statuses.get(1)).isIn(201, 404);
        assertThat(assignments(org.orgId(), dev)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM org_team.memberships WHERE org_id = ? AND user_id = ?",
                        String.class,
                        org.orgId(),
                        dev))
                .isEqualTo("REMOVED");
    }

    @RepeatedTest(5)
    void aTeamDeleteRacingATeamAddLeavesNoOrphanAndNoServerError() throws Exception {
        TestOrg org = newTeamOrg();
        UUID team = seedTeam(org.orgId(), "platform");
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        List<Integer> statuses = race(
                () -> statusOf(perform(delete(TEAMS + "/{id}", org.orgId(), team), org.orgId(), org.owner())),
                () -> statusOf(send(
                        post(TEAMS + "/{id}/members", org.orgId(), team),
                        "{\"userId\":\"" + dev + "\"}",
                        org.orgId(),
                        org.owner())));

        assertThat(statuses.get(0)).isEqualTo(204);
        assertThat(statuses.get(1)).isIn(201, 404);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.team_members WHERE team_id = ?", Integer.class, team))
                .isZero();
    }
}
