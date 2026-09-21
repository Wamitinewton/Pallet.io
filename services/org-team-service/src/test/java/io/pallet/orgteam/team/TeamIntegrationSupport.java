package io.pallet.orgteam.team;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.orgteam.security.OrgFixtures;
import io.pallet.orgteam.security.OrgTeamTestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

public abstract class TeamIntegrationSupport {

    protected static final String TEAMS = "/api/v1/org-team/orgs/{orgId}/teams";
    protected static final String MEMBERS = "/api/v1/org-team/orgs/{orgId}/members";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    protected OrgFixtures fixtures;
    private final List<String> orgIds = new ArrayList<>();

    @BeforeEach
    void setUpFixtures() {
        fixtures = new OrgFixtures(jdbc);
    }

    @AfterEach
    void cleanUpFixtures() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.apps WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.team_members WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.teams WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId);
        });
        fixtures.cleanUp();
        orgIds.clear();
    }

    protected TestOrg newTeamOrg() {
        String orgId = fixtures.newActiveOrg();
        orgIds.add(orgId);
        String owner = fixtures.newMember(orgId, "OWNER", "ACTIVE");
        jdbc.update("UPDATE org_team.organizations SET owner_user_id = ? WHERE org_id = ?", owner, orgId);
        return new TestOrg(orgId, owner);
    }

    protected record TestOrg(String orgId, String owner) {}

    protected ResultActions perform(MockHttpServletRequestBuilder request, String orgId, String userId)
            throws Exception {
        return mvc.perform(request.header(
                "Authorization",
                "Bearer " + OrgTeamTestTokens.forMember(orgId, userId).signed()));
    }

    protected ResultActions send(MockHttpServletRequestBuilder request, String body, String orgId, String userId)
            throws Exception {
        return perform(request.contentType(MediaType.APPLICATION_JSON).content(body), orgId, userId);
    }

    protected UUID seedTeam(String orgId, String slug) {
        UUID teamId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO org_team.teams (id, org_id, name, slug) VALUES (?, ?, ?, ?)", teamId, orgId, slug, slug);
        return teamId;
    }

    protected void seedAssignment(String orgId, UUID teamId, String userId) {
        jdbc.update(
                "INSERT INTO org_team.team_members (team_id, user_id, org_id, added_by) VALUES (?, ?, ?, 'seed')",
                teamId,
                userId,
                orgId);
    }

    protected int assignments(String orgId, String userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.team_members WHERE org_id = ? AND user_id = ?",
                Integer.class,
                orgId,
                userId);
    }

    protected List<String> auditActions(String orgId) {
        return jdbc.queryForList(
                "SELECT payload->>'action' FROM org_team.outbox_events WHERE org_id = ? AND event_type = ? ORDER BY id",
                String.class,
                orgId,
                "audit.event.recorded");
    }

    @SafeVarargs
    protected static List<Integer> race(Callable<Integer>... tasks) throws Exception {
        return raceAll(List.of(tasks));
    }

    protected static List<Integer> raceAll(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    protected int statusOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getStatus();
    }
}
