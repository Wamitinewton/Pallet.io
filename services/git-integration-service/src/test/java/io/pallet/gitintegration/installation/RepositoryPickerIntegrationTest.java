package io.pallet.gitintegration.installation;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.session.GitHubUserSessionStore;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, Tokens.LocalDecoder.class})
class RepositoryPickerIntegrationTest {

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        GitHubApiStub.register(registry);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GitHubUserSessionStore sessions;

    private ReadModelFixtures fixtures;
    private InstallationApi api;
    private final List<String> subjects = new ArrayList<>();
    private String orgId;
    private long installationId;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        api = new InstallationApi(mvc, json, github);
        orgId = fixtures.newOrg();
        installationId = fixtures.newInstallation();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        api.as(member(orgId, "developer"));
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
        subjects.forEach(sub -> {
            sessions.delete(sub);
            jdbc.update("DELETE FROM git_integration.authorization_states WHERE user_id = ?", sub);
        });
    }

    @Test
    void theResponseIsExactlyWhatTheUsersTokenReturnsNotTheInstallationsList() throws Exception {
        api.signIn();
        github.stubUserInstallationRepositories(installationId);
        github.stubTokenMint(installationId);
        github.stubInstallationRepositories();

        MockHttpServletResponse response = api.repositories(orgId, installationId, Map.of());

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode page = api.data(response);
        assertThat(repoIds(page)).containsExactly(42L, 43L, 45L, 46L);
        assertThat(page.path("totalElements").asLong()).isEqualTo(4);
        assertThat(response.getContentAsString()).doesNotContain("payroll");
        LoggedRequest listed = github.requests(GitHubApiStub.userInstallationRepositoriesPath(installationId))
                .getFirst();
        assertThat(listed.getHeader("Authorization")).isEqualTo("Bearer ghu_FixtureUserToken0000000000000000000");
        assertThat(listed.queryParameter("page").firstValue()).isEqualTo("1");
        assertThat(github.requests(GitHubApiStub.INSTALLATION_REPOSITORIES_PATH))
                .isEmpty();
        assertThat(github.mintRequests(installationId)).isEmpty();
    }

    @Test
    void linkableFollowsTheCallersPermissionAndArchived() throws Exception {
        api.signIn();
        github.stubUserInstallationRepositories(installationId);

        JsonNode content =
                api.data(api.repositories(orgId, installationId, Map.of())).path("content");

        JsonNode api42 = repository(content, 42);
        assertThat(api42.path("permission").asString()).isEqualTo("push");
        assertThat(api42.path("linkable").asBoolean()).isTrue();
        assertThat(api42.path("private").asBoolean()).isTrue();
        assertThat(api42.path("defaultBranch").asString()).isEqualTo("main");
        assertThat(repository(content, 43).path("permission").asString()).isEqualTo("maintain");
        assertThat(repository(content, 43).path("linkable").asBoolean()).isTrue();
        assertThat(repository(content, 45).path("permission").asString()).isEqualTo("admin");
        assertThat(repository(content, 45).path("archived").asBoolean()).isTrue();
        assertThat(repository(content, 45).path("linkable").asBoolean()).isFalse();
        assertThat(repository(content, 46).path("permission").asString()).isEqualTo("pull");
        assertThat(repository(content, 46).path("linkable").asBoolean()).isFalse();
    }

    @Test
    void qFiltersByNamePrefixIgnoringCase() throws Exception {
        api.signIn();
        github.stubUserInstallationRepositories(installationId);

        assertThat(repoIds(api.data(api.repositories(orgId, installationId, Map.of("q", "API")))))
                .containsExactly(42L, 46L);
        assertThat(repoIds(api.data(api.repositories(orgId, installationId, Map.of("q", "web")))))
                .containsExactly(43L);
        JsonNode none = api.data(api.repositories(orgId, installationId, Map.of("q", "octo")));
        assertThat(repoIds(none)).isEmpty();
        assertThat(none.path("totalElements").asLong()).isZero();
    }

    @Test
    void anotherOrgsInstallationIsNotFound() throws Exception {
        api.signIn();
        String otherOrg = fixtures.newOrg();
        long theirs = fixtures.newInstallation();
        fixtures.linkInstallation(theirs, otherOrg, "ACTIVE");
        github.stubUserInstallationRepositories(theirs);

        api.assertError(api.repositories(orgId, theirs, Map.of()), 404).hasErrorCode("INSTALLATION_NOT_FOUND");

        assertThat(github.requests(GitHubApiStub.userInstallationRepositoriesPath(theirs)))
                .isEmpty();
    }

    @Test
    void anInstallationTheCallersGitHubUserCantSeeIsNotAccessible() throws Exception {
        api.signIn();
        github.stubNotFound(GitHubApiStub.userInstallationRepositoriesPath(installationId));

        api.assertError(api.repositories(orgId, installationId, Map.of()), 403)
                .hasErrorCode("INSTALLATION_NOT_ACCESSIBLE");
    }

    @Test
    void withoutASessionTheCallerMustAuthorize() throws Exception {
        api.assertError(api.repositories(orgId, installationId, Map.of()), 403)
                .hasErrorCode("GITHUB_AUTHORIZATION_REQUIRED");

        assertThat(github.totalRequests()).isZero();
    }

    @Test
    void aViewerCantUseThePicker() throws Exception {
        api.as(member(orgId, "viewer"));

        api.assertError(api.repositories(orgId, installationId, Map.of()), 403).hasErrorCode("INSUFFICIENT_ROLE");
    }

    private String member(String org, String role) {
        String sub = fixtures.newMember(org, role, "ACTIVE");
        subjects.add(sub);
        return sub;
    }

    private static List<Long> repoIds(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("content").forEach(repo -> ids.add(repo.path("repoId").asLong()));
        return ids;
    }

    private static JsonNode repository(JsonNode content, long repoId) {
        for (JsonNode repo : content) {
            if (repo.path("repoId").asLong() == repoId) {
                return repo;
            }
        }
        throw new AssertionError("No repository " + repoId + " in " + content);
    }
}
