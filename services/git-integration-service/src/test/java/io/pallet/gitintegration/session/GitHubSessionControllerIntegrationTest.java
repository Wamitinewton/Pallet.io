package io.pallet.gitintegration.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.assertions.ErrorResponseAssert;
import io.pallet.common.test.assertions.PalletAssertions;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.Tokens;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, Tokens.LocalDecoder.class})
class GitHubSessionControllerIntegrationTest {

    private static final String BASE = "/api/v1/git-integration/github";
    private static final String CODE = "0123456789abcdef0123";
    private static final List<String> TOKEN_SHAPED = List.of("ghu_", "ghr_", "token", "refresh", "secret");

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        GitHubApiStub.register(registry);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private GitHubUserSessionStore store;

    @Autowired
    private JdbcTemplate jdbc;

    private final String sub = "user-" + UUID.randomUUID();
    private String bearer;

    @BeforeEach
    void setUp() {
        bearer = "Bearer " + Tokens.forUser(sub).signed();
    }

    @AfterEach
    void tearDown() {
        store.delete(sub);
        jdbc.update("DELETE FROM git_integration.authorization_states WHERE user_id = ?", sub);
    }

    @Test
    void startThenCompleteThenTheSessionShowsTheLogin() throws Exception {
        github.stubCodeExchange();
        github.stubCurrentUser();

        MockHttpServletResponse started = perform(post(BASE + "/authorizations"));
        assertThat(started.getStatus()).isEqualTo(201);
        UriComponents authorizeUrl = UriComponentsBuilder.fromUriString(
                        data(started).path("authorizeUrl").asString())
                .build();
        assertThat(authorizeUrl.getPath()).isEqualTo("/login/oauth/authorize");
        assertThat(authorizeUrl.getQueryParams().getFirst("client_id")).isEqualTo("test-client-id");
        assertThat(authorizeUrl.getQueryParams()).containsKey("state").doesNotContainKey("redirect_uri");

        MockHttpServletResponse completed =
                complete(authorizeUrl.getQueryParams().getFirst("state"));
        assertThat(completed.getStatus()).isEqualTo(200);
        assertThat(data(completed).path("githubLogin").asString()).isEqualTo("fixture-dev");

        MockHttpServletResponse session = perform(get(BASE + "/session"));
        assertThat(session.getStatus()).isEqualTo(200);
        assertThat(data(session).path("githubLogin").asString()).isEqualTo("fixture-dev");
        assertThat(data(session).path("expiresAt").isMissingNode()).isFalse();

        for (MockHttpServletResponse response : List.of(started, completed, session)) {
            assertNoTokenShapedField(response);
        }
        List<LoggedRequest> exchanges = github.requests(GitHubApiStub.OAUTH_TOKEN_PATH);
        assertThat(exchanges).hasSize(1);
        assertThat(exchanges.getFirst().getBodyAsString()).contains(CODE).doesNotContain("redirect_uri");
        assertThat(github.requests(GitHubApiStub.USER_PATH).getFirst().getHeader("Authorization"))
                .isEqualTo("Bearer ghu_FixtureUserToken0000000000000000000");
        assertNoRefreshTokenUsed();
    }

    @Test
    void aReplayedCompleteIsRejected() throws Exception {
        github.stubCodeExchange();
        github.stubCurrentUser();
        String state = startedState();

        assertThat(complete(state).getStatus()).isEqualTo(200);

        assertError(complete(state), 400).hasErrorCode("INVALID_AUTHORIZATION_STATE");
        assertThat(github.requests(GitHubApiStub.OAUTH_TOKEN_PATH)).hasSize(1);
    }

    @Test
    void aBadVerificationCodeFromGitHubIsRejected() throws Exception {
        github.stubCodeRejected();

        assertError(complete(startedState()), 400).hasErrorCode("INVALID_AUTHORIZATION_STATE");
        assertThat(perform(get(BASE + "/session")).getStatus()).isEqualTo(404);
    }

    @Test
    void aCodeOutsideGitHubsCharsetIsRejectedBeforeTheStateIsSpent() throws Exception {
        String state = startedState();

        MockHttpServletResponse response = perform(post(BASE + "/authorizations/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("code", "bad code/..", "state", state))));

        assertError(response, 400).hasErrorCode("INVALID_AUTHORIZATION_STATE");
        assertThat(github.totalRequests()).isZero();
    }

    @Test
    void anotherUsersStateIsRejected() throws Exception {
        String state = startedState();
        bearer = "Bearer " + Tokens.forUser("user-" + UUID.randomUUID()).signed();

        assertError(complete(state), 400).hasErrorCode("INVALID_AUTHORIZATION_STATE");
    }

    @Test
    void noSessionIs404AndDeleteIs204() throws Exception {
        assertError(perform(get(BASE + "/session")), 404).hasErrorCode("GITHUB_SESSION_NOT_FOUND");
        assertThat(perform(delete(BASE + "/session")).getStatus()).isEqualTo(204);
    }

    @Test
    void deletingTheSessionEndsIt() throws Exception {
        signIn();

        assertThat(perform(delete(BASE + "/session")).getStatus()).isEqualTo(204);

        assertThat(perform(get(BASE + "/session")).getStatus()).isEqualTo(404);
        assertThat(store.find(sub)).isEmpty();
    }

    @Test
    void installationsWithoutASessionRequireAuthorization() throws Exception {
        assertError(perform(get(BASE + "/installations")), 403).hasErrorCode("GITHUB_AUTHORIZATION_REQUIRED");
        assertThat(github.requests(GitHubApiStub.USER_INSTALLATIONS_PATH)).isEmpty();
    }

    @Test
    void installationsListWhatTheUsersTokenReturns() throws Exception {
        signIn();
        github.stubUserInstallations();

        MockHttpServletResponse response = perform(get(BASE + "/installations").param("size", "2"));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode page = data(response);
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        JsonNode first = page.path("content").get(0);
        assertThat(first.path("installationId").asLong()).isEqualTo(51000001L);
        assertThat(first.path("accountLogin").asString()).isEqualTo("octo-org");
        assertThat(first.path("accountType").asString()).isEqualTo("Organization");
        assertThat(first.path("suspended").asBoolean()).isFalse();
        assertThat(page.path("content").get(1).path("suspended").asBoolean()).isTrue();
        LoggedRequest listed =
                github.requests(GitHubApiStub.USER_INSTALLATIONS_PATH).getFirst();
        assertThat(listed.queryParameter("page").firstValue()).isEqualTo("1");
        assertThat(listed.queryParameter("per_page").firstValue()).isEqualTo("2");
        assertNoTokenShapedField(response);
    }

    @Test
    void aRejectedUserTokenEndsTheSession() throws Exception {
        signIn();
        github.stubUnauthorized(GitHubApiStub.USER_INSTALLATIONS_PATH);

        assertError(perform(get(BASE + "/installations")), 403).hasErrorCode("GITHUB_AUTHORIZATION_REQUIRED");

        assertThat(store.find(sub)).isEmpty();
        assertThat(github.requests(GitHubApiStub.USER_INSTALLATIONS_PATH)).hasSize(1);
    }

    @Test
    void anUnauthenticatedCallerIs401() throws Exception {
        assertThat(mvc.perform(post(BASE + "/authorizations"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    private void signIn() throws Exception {
        github.stubCodeExchange();
        github.stubCurrentUser();
        assertThat(complete(startedState()).getStatus()).isEqualTo(200);
    }

    private String startedState() throws Exception {
        String url = data(perform(post(BASE + "/authorizations")))
                .path("authorizeUrl")
                .asString();
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
    }

    private MockHttpServletResponse complete(String state) throws Exception {
        return perform(post(BASE + "/authorizations/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("code", CODE, "state", state))));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer)).andReturn().getResponse();
    }

    private JsonNode data(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString()).path("data");
    }

    private ErrorResponseAssert assertError(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        return PalletAssertions.assertThat(jsonMapper.readValue(response.getContentAsString(), ErrorResponse.class))
                .isFailure()
                .hasStatusCode(status);
    }

    private static void assertNoTokenShapedField(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString().toLowerCase(Locale.ROOT);
        for (String shape : TOKEN_SHAPED) {
            assertThat(body).doesNotContain(shape);
        }
    }

    private static void assertNoRefreshTokenUsed() {
        for (LoggedRequest request : github.allRequests()) {
            assertThat(request.getUrl()).doesNotContain("refresh");
            assertThat(request.getBodyAsString()).doesNotContain("ghr_").doesNotContain("refresh_token");
            String authorization = request.getHeader("Authorization");
            assertThat(authorization == null ? "" : authorization).doesNotContain("ghr_");
        }
    }
}
