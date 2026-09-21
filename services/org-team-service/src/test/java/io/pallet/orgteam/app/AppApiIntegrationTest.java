package io.pallet.orgteam.app;

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
import io.pallet.orgteam.team.TeamIntegrationSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
class AppApiIntegrationTest extends TeamIntegrationSupport {

    private static final String APPS = "/api/v1/org-team/orgs/{orgId}/apps";
    private static final int APP_QUOTA = 200;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private TransactionTemplate transactions;

    private static String body(String name, String provider, String region) {
        return "{\"name\":\"" + name + "\",\"cloudProvider\":\"" + provider + "\",\"region\":\"" + region + "\"}";
    }

    private String createApp(TestOrg org, String name) throws Exception {
        String created = send(post(APPS, org.orgId()), body(name, "AWS", "us-east-1"), org.orgId(), org.owner())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(created, "$.data.id");
    }

    private UUID seedApp(String orgId, String slug, UUID teamId) {
        UUID appId = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO org_team.apps (id, org_id, team_id, name, slug, cloud_provider, region, status)
                        VALUES (?, ?, ?, ?, ?, 'AWS', 'us-east-1', 'ACTIVE')
                        """, appId, orgId, teamId, slug, slug);
        return appId;
    }

    private void seedManyApps(String orgId, int count) {
        jdbc.update("""
                        INSERT INTO org_team.apps (id, org_id, name, slug, cloud_provider, region, status)
                        SELECT gen_random_uuid(), ?, 'bulk-' || n, 'bulk-' || n, 'AWS', 'us-east-1', 'ACTIVE'
                        FROM generate_series(1, ?) AS n
                        """, orgId, count);
    }

    private List<Map<String, Object>> events(String orgId, String eventType) {
        return jdbc.queryForList(
                "SELECT payload::text AS payload FROM org_team.outbox_events WHERE org_id = ? AND event_type = ? ORDER BY id",
                orgId,
                eventType);
    }

    private String column(String orgId, UUID appId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + "::text FROM org_team.apps WHERE org_id = ? AND id = ?",
                String.class,
                orgId,
                appId);
    }

    @Test
    void anAppGoesThroughItsWholeLifecycle() throws Exception {
        TestOrg org = newTeamOrg();
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        String viewer = fixtures.newMember(org.orgId(), "VIEWER", "ACTIVE");

        String created = send(post(APPS, org.orgId()), body("My Web App", "AWS", "us-east-1"), org.orgId(), dev)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").value("my-web-app"))
                .andExpect(jsonPath("$.data.cloudProvider").value("AWS"))
                .andExpect(jsonPath("$.data.region").value("us-east-1"))
                .andExpect(jsonPath("$.data.teamId").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String appId = JsonPath.read(created, "$.data.id");

        assertThat(column(org.orgId(), UUID.fromString(appId), "status")).isEqualTo("ACTIVE");
        assertThat(events(org.orgId(), "app.created")).singleElement().satisfies(row -> {
            String payload = (String) row.get("payload");
            assertThat(JsonPath.<String>read(payload, "$.appId")).isEqualTo(appId);
            assertThat(JsonPath.<String>read(payload, "$.slug")).isEqualTo("my-web-app");
            assertThat(JsonPath.<String>read(payload, "$.cloudProvider")).isEqualTo("AWS");
            assertThat(JsonPath.<String>read(payload, "$.region")).isEqualTo("us-east-1");
            assertThat(JsonPath.<String>read(payload, "$.createdByUserId")).isEqualTo(dev);
        });

        perform(get(APPS + "/{appId}", org.orgId(), appId), org.orgId(), viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("My Web App"))
                .andExpect(jsonPath("$.data.version").doesNotExist());

        send(patch(APPS + "/{appId}", org.orgId(), appId), "{\"name\":\"Storefront\"}", org.orgId(), dev)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Storefront"))
                .andExpect(jsonPath("$.data.slug").value("my-web-app"))
                .andExpect(jsonPath("$.data.region").value("us-east-1"));

        perform(delete(APPS + "/{appId}", org.orgId(), appId), org.orgId(), org.owner())
                .andExpect(status().isNoContent());
        assertThat(column(org.orgId(), UUID.fromString(appId), "status")).isEqualTo("DELETED");
        assertThat(column(org.orgId(), UUID.fromString(appId), "deleted_at")).isNotNull();
        assertThat(events(org.orgId(), "app.deleted")).singleElement().satisfies(row -> {
            String payload = (String) row.get("payload");
            assertThat(JsonPath.<String>read(payload, "$.appId")).isEqualTo(appId);
            assertThat(JsonPath.<String>read(payload, "$.slug")).isEqualTo("my-web-app");
            assertThat(JsonPath.<String>read(payload, "$.deletedByUserId")).isEqualTo(org.owner());
        });

        perform(get(APPS + "/{appId}", org.orgId(), appId), org.orgId(), viewer)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("APP_NOT_FOUND"));
        perform(delete(APPS + "/{appId}", org.orgId(), appId), org.orgId(), org.owner())
                .andExpect(status().isNotFound());
        assertThat(events(org.orgId(), "app.deleted")).hasSize(1);
        assertThat(auditActions(org.orgId())).containsExactly("app.created", "app.updated", "app.deleted");
    }

    @Test
    void aDuplicateSlugConflictsUntilTheFirstAppIsDeleted() throws Exception {
        TestOrg org = newTeamOrg();
        String first = createApp(org, "Web");

        send(post(APPS, org.orgId()), body("Web", "GCP", "us-central1"), org.orgId(), org.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLUG_TAKEN"));
        assertThat(events(org.orgId(), "app.created")).hasSize(1);

        perform(delete(APPS + "/{appId}", org.orgId(), first), org.orgId(), org.owner())
                .andExpect(status().isNoContent());
        send(post(APPS, org.orgId()), body("Web", "GCP", "us-central1"), org.orgId(), org.owner())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").value("web"));
        assertThat(events(org.orgId(), "app.created")).hasSize(2);
    }

    @Test
    void twoOrgsMayUseTheSameSlug() throws Exception {
        TestOrg first = newTeamOrg();
        TestOrg second = newTeamOrg();

        createApp(first, "Web");
        createApp(second, "Web");
    }

    @Test
    void anUnlistedRegionOrOneFromAnotherProviderIsInvalidRegion() throws Exception {
        TestOrg org = newTeamOrg();

        send(post(APPS, org.orgId()), body("Web", "AWS", "nowhere"), org.orgId(), org.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REGION"));
        send(post(APPS, org.orgId()), body("Web", "AWS", "us-central1"), org.orgId(), org.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REGION"));
        send(post(APPS, org.orgId()), body("Web", "AZURE", "westus"), org.orgId(), org.owner())
                .andExpect(status().isBadRequest());
        assertThat(events(org.orgId(), "app.created")).isEmpty();
    }

    @Test
    void writesFollowTheRoleFloorAndReadsNeedOnlyMembership() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        String viewer = fixtures.newMember(org.orgId(), "VIEWER", "ACTIVE");
        UUID app = seedApp(org.orgId(), "web", null);

        send(post(APPS, org.orgId()), body("Nope", "AWS", "us-east-1"), org.orgId(), viewer)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
        send(patch(APPS + "/{id}", org.orgId(), app), "{\"name\":\"Nope\"}", org.orgId(), viewer)
                .andExpect(status().isForbidden());
        for (String caller : List.of(dev, viewer)) {
            perform(delete(APPS + "/{id}", org.orgId(), app), org.orgId(), caller)
                    .andExpect(status().isForbidden());
        }
        for (String caller : List.of(admin, dev, viewer)) {
            perform(get(APPS, org.orgId()), org.orgId(), caller).andExpect(status().isOk());
            perform(get(APPS + "/{id}", org.orgId(), app), org.orgId(), caller).andExpect(status().isOk());
        }
        send(post(APPS, org.orgId()), body("Dev App", "AWS", "us-east-1"), org.orgId(), dev)
                .andExpect(status().isCreated());
        send(patch(APPS + "/{id}", org.orgId(), app), "{\"name\":\"Renamed\"}", org.orgId(), dev)
                .andExpect(status().isOk());
        perform(delete(APPS + "/{id}", org.orgId(), app), org.orgId(), admin).andExpect(status().isNoContent());
    }

    @Test
    void aDemotedDeveloperLosesAppWritesImmediately() throws Exception {
        TestOrg org = newTeamOrg();
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");
        fixtures.setRole(org.orgId(), dev, "VIEWER");

        send(post(APPS, org.orgId()), body("Web", "AWS", "us-east-1"), org.orgId(), dev)
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherOrgsAppIsNotFoundAndCannotBeChangedOrDeleted() throws Exception {
        TestOrg mine = newTeamOrg();
        TestOrg theirs = newTeamOrg();
        UUID theirApp = seedApp(theirs.orgId(), "web", null);

        perform(get(APPS + "/{id}", mine.orgId(), theirApp), mine.orgId(), mine.owner())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("APP_NOT_FOUND"));
        send(patch(APPS + "/{id}", mine.orgId(), theirApp), "{\"name\":\"x\"}", mine.orgId(), mine.owner())
                .andExpect(status().isNotFound());
        perform(delete(APPS + "/{id}", mine.orgId(), theirApp), mine.orgId(), mine.owner())
                .andExpect(status().isNotFound());
        perform(get(APPS, theirs.orgId()), mine.orgId(), mine.owner()).andExpect(status().isNotFound());
        assertThat(column(theirs.orgId(), theirApp, "status")).isEqualTo("ACTIVE");
        assertThat(column(theirs.orgId(), theirApp, "name")).isEqualTo("web");
    }

    @Test
    void aTeamFromAnotherOrgIsRejectedByTheApiAndByTheCompositeForeignKey() throws Exception {
        TestOrg mine = newTeamOrg();
        TestOrg theirs = newTeamOrg();
        UUID foreignTeam = seedTeam(theirs.orgId(), "platform");
        UUID ownApp = seedApp(mine.orgId(), "web", null);

        send(
                        post(APPS, mine.orgId()),
                        "{\"name\":\"Web2\",\"cloudProvider\":\"AWS\",\"region\":\"us-east-1\",\"teamId\":\""
                                + foreignTeam + "\"}",
                        mine.orgId(),
                        mine.owner())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TEAM_NOT_FOUND"));
        send(
                        patch(APPS + "/{id}", mine.orgId(), ownApp),
                        "{\"teamId\":\"" + foreignTeam + "\"}",
                        mine.orgId(),
                        mine.owner())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TEAM_NOT_FOUND"));
        assertThat(column(mine.orgId(), ownApp, "team_id")).isNull();

        assertThatThrownBy(() -> seedApp(mine.orgId(), "raw", foreignTeam))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheTeamDetachesItsAppsWithoutTouchingThem() throws Exception {
        TestOrg org = newTeamOrg();
        UUID team = seedTeam(org.orgId(), "platform");
        String created = send(
                        post(APPS, org.orgId()),
                        "{\"name\":\"Web\",\"cloudProvider\":\"AWS\",\"region\":\"us-east-1\",\"teamId\":\"" + team
                                + "\"}",
                        org.orgId(),
                        org.owner())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.teamId").value(team.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID appId = UUID.fromString(JsonPath.read(created, "$.data.id"));

        perform(delete("/api/v1/org-team/orgs/{orgId}/teams/{teamId}", org.orgId(), team), org.orgId(), org.owner())
                .andExpect(status().isNoContent());

        assertThat(column(org.orgId(), appId, "team_id")).isNull();
        assertThat(column(org.orgId(), appId, "status")).isEqualTo("ACTIVE");
        perform(get(APPS + "/{id}", org.orgId(), appId), org.orgId(), org.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamId").doesNotExist())
                .andExpect(jsonPath("$.data.region").value("us-east-1"));
    }

    @Test
    void patchDistinguishesAbsentNullAndPresentTeamId() throws Exception {
        TestOrg org = newTeamOrg();
        UUID team = seedTeam(org.orgId(), "platform");
        UUID app = seedApp(org.orgId(), "web", team);
        String url = APPS + "/{id}";

        send(patch(url, org.orgId(), app), "{\"name\":\"Renamed\"}", org.orgId(), org.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamId").value(team.toString()));
        send(patch(url, org.orgId(), app), "{\"teamId\":null}", org.orgId(), org.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamId").doesNotExist())
                .andExpect(jsonPath("$.data.name").value("Renamed"));
        assertThat(column(org.orgId(), app, "team_id")).isNull();
        send(patch(url, org.orgId(), app), "{\"teamId\":\"" + team + "\"}", org.orgId(), org.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamId").value(team.toString()));
        assertThat(column(org.orgId(), app, "team_id")).isEqualTo(team.toString());
    }

    @Test
    void anUnchangedPatchIsOkAndWritesAndEmitsNothing() throws Exception {
        TestOrg org = newTeamOrg();
        UUID team = seedTeam(org.orgId(), "platform");
        UUID app = seedApp(org.orgId(), "web", team);
        String before = column(org.orgId(), app, "updated_at");
        String url = APPS + "/{id}";

        send(patch(url, org.orgId(), app), "{}", org.orgId(), org.owner()).andExpect(status().isOk());
        send(patch(url, org.orgId(), app), "{\"name\":\"web\"}", org.orgId(), org.owner())
                .andExpect(status().isOk());
        send(patch(url, org.orgId(), app), "{\"teamId\":\"" + team + "\"}", org.orgId(), org.owner())
                .andExpect(status().isOk());

        assertThat(column(org.orgId(), app, "updated_at")).isEqualTo(before);
        assertThat(auditActions(org.orgId())).isEmpty();
    }

    @Test
    void listFiltersSortsAndPagesActiveAppsOnly() throws Exception {
        TestOrg org = newTeamOrg();
        UUID team = seedTeam(org.orgId(), "platform");
        seedApp(org.orgId(), "beta", team);
        seedApp(org.orgId(), "alpha", null);
        jdbc.update(
                "INSERT INTO org_team.apps (id, org_id, name, slug, cloud_provider, region, status)"
                        + " VALUES (?, ?, 'gamma', 'gamma', 'GCP', 'us-central1', 'ACTIVE')",
                UUID.randomUUID(),
                org.orgId());
        jdbc.update(
                "INSERT INTO org_team.apps (id, org_id, name, slug, cloud_provider, region, status, deleted_at)"
                        + " VALUES (?, ?, 'gone', 'gone', 'AWS', 'us-east-1', 'DELETED', now())",
                UUID.randomUUID(),
                org.orgId());
        String viewer = fixtures.newMember(org.orgId(), "VIEWER", "ACTIVE");

        perform(get(APPS, org.orgId()).param("sort", "name"), org.orgId(), viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].slug", contains("alpha", "beta", "gamma")))
                .andExpect(jsonPath("$.data.totalElements").value(3));
        perform(get(APPS, org.orgId()).param("sort", "name,desc").param("size", "2"), org.orgId(), viewer)
                .andExpect(jsonPath("$.data.content[*].slug", contains("gamma", "beta")))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        perform(get(APPS, org.orgId()).param("teamId", team.toString()), org.orgId(), viewer)
                .andExpect(jsonPath("$.data.content[*].slug", contains("beta")));
        perform(get(APPS, org.orgId()).param("cloudProvider", "GCP"), org.orgId(), viewer)
                .andExpect(jsonPath("$.data.content[*].slug", contains("gamma")));
        perform(
                        get(APPS, org.orgId())
                                .param("cloudProvider", "AWS")
                                .param("teamId", team.toString())
                                .param("sort", "name"),
                        org.orgId(),
                        viewer)
                .andExpect(jsonPath("$.data.content[*].slug", contains("beta")));
        perform(get(APPS, org.orgId()).param("teamId", UUID.randomUUID().toString()), org.orgId(), viewer)
                .andExpect(jsonPath("$.data.totalElements").value(0));
        perform(get(APPS, org.orgId()).param("sort", "region"), org.orgId(), viewer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SORT"));
        perform(get(APPS, org.orgId()).param("cloudProvider", "AZURE"), org.orgId(), viewer)
                .andExpect(status().isBadRequest());
    }

    @Test
    void theActiveAppQuotaIsEnforcedAndDeletedAppsDoNotCountAgainstIt() throws Exception {
        TestOrg org = newTeamOrg();
        seedManyApps(org.orgId(), APP_QUOTA);

        send(post(APPS, org.orgId()), body("One Too Many", "AWS", "us-east-1"), org.orgId(), org.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("QUOTA_EXCEEDED"));
        assertThat(events(org.orgId(), "app.created")).isEmpty();

        jdbc.update(
                "UPDATE org_team.apps SET status = 'DELETED', deleted_at = now() WHERE org_id = ? AND slug = 'bulk-1'",
                org.orgId());
        send(post(APPS, org.orgId()), body("Fits Now", "AWS", "us-east-1"), org.orgId(), org.owner())
                .andExpect(status().isCreated());
    }

    @Test
    void twentyConcurrentCreatesOfOneSlugYieldExactlyOneApp() throws Exception {
        TestOrg org = newTeamOrg();

        List<Callable<Integer>> creates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            creates.add(() ->
                    statusOf(send(post(APPS, org.orgId()), body("Web", "AWS", "us-east-1"), org.orgId(), org.owner())));
        }
        List<Integer> statuses = raceAll(creates);

        assertThat(statuses.stream().filter(code -> code == 201)).hasSize(1);
        assertThat(statuses.stream().filter(code -> code == 409)).hasSize(19);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.apps WHERE org_id = ? AND slug = 'web'",
                        Integer.class,
                        org.orgId()))
                .isEqualTo(1);
        assertThat(events(org.orgId(), "app.created")).hasSize(1);
    }

    @Test
    void concurrentCreatesNeverExceedTheQuota() throws Exception {
        TestOrg org = newTeamOrg();
        seedManyApps(org.orgId(), APP_QUOTA - 3);

        List<Callable<Integer>> creates = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String name = "Racer " + i;
            creates.add(() ->
                    statusOf(send(post(APPS, org.orgId()), body(name, "AWS", "us-east-1"), org.orgId(), org.owner())));
        }
        List<Integer> statuses = raceAll(creates);

        assertThat(statuses.stream().filter(code -> code == 201)).hasSize(3);
        assertThat(statuses.stream().filter(code -> code == 409)).hasSize(9);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.apps WHERE org_id = ? AND status = 'ACTIVE'",
                        Integer.class,
                        org.orgId()))
                .isEqualTo(APP_QUOTA);
    }

    @Test
    void placementCannotBeChangedThroughTheApi() throws Exception {
        TestOrg org = newTeamOrg();
        UUID app = seedApp(org.orgId(), "web", null);

        for (String forbidden : List.of(
                "{\"region\":\"eu-west-1\"}",
                "{\"cloudProvider\":\"GCP\"}",
                "{\"slug\":\"other\"}",
                "{\"name\":\"ok\",\"region\":\"eu-west-1\"}")) {
            send(patch(APPS + "/{id}", org.orgId(), app), forbidden, org.orgId(), org.owner())
                    .andExpect(status().isBadRequest());
        }

        assertThat(column(org.orgId(), app, "region")).isEqualTo("us-east-1");
        assertThat(column(org.orgId(), app, "cloud_provider")).isEqualTo("AWS");
        assertThat(column(org.orgId(), app, "slug")).isEqualTo("web");
        assertThat(column(org.orgId(), app, "name")).isEqualTo("web");
    }

    @Test
    void placementCannotBeChangedThroughTheEntityMapping() {
        TestOrg org = newTeamOrg();
        UUID appId = seedApp(org.orgId(), "web", null);

        transactions.executeWithoutResult(tx -> {
            App app = appRepository.findById(appId).orElseThrow();
            ReflectionTestUtils.setField(app, "region", "eu-west-1");
            ReflectionTestUtils.setField(app, "cloudProvider", CloudProvider.GCP);
            ReflectionTestUtils.setField(app, "slug", "hijacked");
            ReflectionTestUtils.setField(app, "name", "Renamed");
            appRepository.saveAndFlush(app);
        });

        assertThat(column(org.orgId(), appId, "name")).isEqualTo("Renamed");
        assertThat(column(org.orgId(), appId, "region")).isEqualTo("us-east-1");
        assertThat(column(org.orgId(), appId, "cloud_provider")).isEqualTo("AWS");
        assertThat(column(org.orgId(), appId, "slug")).isEqualTo("web");
    }

    @Test
    void placementCannotBeChangedByRawSql() {
        TestOrg org = newTeamOrg();
        UUID appId = seedApp(org.orgId(), "web", null);

        assertThatThrownBy(() -> jdbc.update("UPDATE org_team.apps SET region = 'eu-west-1' WHERE id = ?", appId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE org_team.apps SET cloud_provider = 'GCP' WHERE id = ?", appId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(column(org.orgId(), appId, "region")).isEqualTo("us-east-1");
        assertThat(column(org.orgId(), appId, "cloud_provider")).isEqualTo("AWS");

        assertThat(jdbc.update("UPDATE org_team.apps SET name = 'still allowed' WHERE id = ?", appId))
                .isEqualTo(1);
    }
}
