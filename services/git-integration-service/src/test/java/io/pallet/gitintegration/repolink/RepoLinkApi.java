package io.pallet.gitintegration.repolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.pallet.common.error.ErrorResponse;
import io.pallet.common.test.assertions.ErrorResponseAssert;
import io.pallet.common.test.assertions.PalletAssertions;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.Tokens;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The repo link endpoints driven through MockMvc as one caller at a time. */
public final class RepoLinkApi {

    private static final String BASE = "/api/v1/git-integration";
    private static final String CODE = "0123456789abcdef0123";

    private final MockMvc mvc;
    private final JsonMapper json;
    private final GitHubApiStub github;
    private String bearer;

    public RepoLinkApi(MockMvc mvc, JsonMapper json, GitHubApiStub github) {
        this.mvc = mvc;
        this.json = json;
        this.github = github;
    }

    public RepoLinkApi as(String sub) {
        bearer = "Bearer " + Tokens.forUser(sub).signed();
        return this;
    }

    /** A GitHub user session through the real authorize flow, with GitHub's side stubbed. */
    public RepoLinkApi signIn() throws Exception {
        github.stubCodeExchange();
        github.stubCurrentUser();
        String url = data(perform(post(BASE + "/github/authorizations")))
                .path("authorizeUrl")
                .asString();
        String state =
                UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
        MockHttpServletResponse completed = perform(post(BASE + "/github/authorizations/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", CODE, "state", state))));
        assertThat(completed.getStatus()).as(completed.getContentAsString()).isEqualTo(200);
        return this;
    }

    public MockHttpServletResponse link(String orgId, UUID appId, Map<String, Object> body) throws Exception {
        return perform(
                put(path(orgId, appId)).contentType(MediaType.APPLICATION_JSON).content(write(body)));
    }

    public MockHttpServletResponse link(String orgId, UUID appId, long installationId, long repoId) throws Exception {
        return link(orgId, appId, Map.of("installationId", installationId, "repoId", repoId));
    }

    public MockHttpServletResponse get(String orgId, UUID appId) throws Exception {
        return perform(MockMvcRequestBuilders.get(path(orgId, appId)));
    }

    public MockHttpServletResponse update(String orgId, UUID appId, Map<String, Object> body) throws Exception {
        return perform(patch(path(orgId, appId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(write(body)));
    }

    public MockHttpServletResponse takeOverVerification(String orgId, UUID appId) throws Exception {
        return perform(post(path(orgId, appId) + "/verification"));
    }

    public MockHttpServletResponse disconnect(String orgId, UUID appId) throws Exception {
        return perform(delete(path(orgId, appId)));
    }

    /** {@code idempotencyKey} null sends no header; {@code body} null sends no body. */
    public MockHttpServletResponse build(String orgId, UUID appId, String idempotencyKey, Map<String, Object> body)
            throws Exception {
        MockHttpServletRequestBuilder request = post(path(orgId, appId) + "/builds");
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(write(body));
        }
        return perform(request);
    }

    public MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer)).andReturn().getResponse();
    }

    public JsonNode data(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).path("data");
    }

    public ErrorResponseAssert assertError(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return PalletAssertions.assertThat(json.readValue(response.getContentAsString(), ErrorResponse.class))
                .isFailure()
                .hasStatusCode(status);
    }

    /** The error body without its timestamp, the only field that differs between two identical refusals. */
    public String errorWithoutTimestamp(MockHttpServletResponse response) throws Exception {
        ObjectNode body = (ObjectNode) json.readTree(response.getContentAsString());
        body.remove("timestamp");
        return json.writeValueAsString(body);
    }

    private String write(Map<String, Object> body) {
        return json.writeValueAsString(new LinkedHashMap<>(body));
    }

    private static String path(String orgId, UUID appId) {
        return BASE + "/orgs/" + orgId + "/apps/" + appId + "/repo-link";
    }
}
