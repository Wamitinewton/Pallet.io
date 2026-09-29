package io.pallet.gitintegration.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.pallet.common.error.ErrorResponse;
import io.pallet.common.test.assertions.ErrorResponseAssert;
import io.pallet.common.test.assertions.PalletAssertions;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.Tokens;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The installation endpoints driven through MockMvc as one caller at a time. */
final class InstallationApi {

    static final String CODE = "0123456789abcdef0123";

    private static final String BASE = "/api/v1/git-integration";

    private final MockMvc mvc;
    private final JsonMapper json;
    private final GitHubApiStub github;
    private String bearer;

    InstallationApi(MockMvc mvc, JsonMapper json, GitHubApiStub github) {
        this.mvc = mvc;
        this.json = json;
        this.github = github;
    }

    InstallationApi as(String sub) {
        bearer = "Bearer " + Tokens.forUser(sub).signed();
        return this;
    }

    /** A GitHub user session through the real authorize flow, with GitHub's side stubbed. */
    void signIn() throws Exception {
        github.stubCodeExchange();
        github.stubCurrentUser();
        String url = data(perform(post(BASE + "/github/authorizations")))
                .path("authorizeUrl")
                .asString();
        MockHttpServletResponse completed = perform(post(BASE + "/github/authorizations/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", CODE, "state", stateOf(url)))));
        assertThat(completed.getStatus()).isEqualTo(200);
    }

    MockHttpServletResponse startInstall(String orgId) throws Exception {
        return perform(post(org(orgId) + "/install-sessions"));
    }

    String installState(String orgId) throws Exception {
        MockHttpServletResponse started = startInstall(orgId);
        assertThat(started.getStatus()).isEqualTo(201);
        return stateOf(data(started).path("installUrl").asString());
    }

    MockHttpServletResponse linkFresh(String orgId, long installationId, String state) throws Exception {
        return link(orgId, Map.of("installationId", installationId, "code", CODE, "state", state));
    }

    MockHttpServletResponse linkExisting(String orgId, long installationId) throws Exception {
        return link(orgId, Map.of("installationId", installationId));
    }

    MockHttpServletResponse link(String orgId, Map<String, Object> body) throws Exception {
        return perform(post(org(orgId) + "/installations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new LinkedHashMap<>(body))));
    }

    MockHttpServletResponse list(String orgId) throws Exception {
        return perform(get(org(orgId) + "/installations"));
    }

    MockHttpServletResponse unlink(String orgId, long installationId) throws Exception {
        return perform(delete(org(orgId) + "/installations/" + installationId));
    }

    MockHttpServletResponse repositories(String orgId, long installationId, Map<String, String> params)
            throws Exception {
        MockHttpServletRequestBuilder request = get(org(orgId) + "/installations/" + installationId + "/repositories");
        params.forEach(request::param);
        return perform(request);
    }

    MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer)).andReturn().getResponse();
    }

    JsonNode data(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).path("data");
    }

    ErrorResponseAssert assertError(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return PalletAssertions.assertThat(json.readValue(response.getContentAsString(), ErrorResponse.class))
                .isFailure()
                .hasStatusCode(status);
    }

    static String stateOf(String url) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
    }

    private static String org(String orgId) {
        return BASE + "/orgs/" + orgId + "/github";
    }
}
