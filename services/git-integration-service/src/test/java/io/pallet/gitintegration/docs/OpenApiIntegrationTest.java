package io.pallet.gitintegration.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import(RedisTestContainerConfiguration.class)
class OpenApiIntegrationTest {

    private static final String BASE = "/api/v1/git-integration";
    private static final String DOCS = BASE + "/v3/api-docs";
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
    private static final List<String> STANDARD_ERRORS = List.of("400", "401", "403", "404", "409", "429", "500");

    private static final String WEBHOOK = "POST " + BASE + "/webhooks/github";
    private static final String GITHUB = BASE + "/github";
    private static final String INSTALLATIONS = BASE + "/orgs/{orgId}/github";
    private static final String REPO_LINK = BASE + "/orgs/{orgId}/apps/{appId}/repo-link";
    private static final String PUT_REPO_LINK = "PUT " + REPO_LINK;
    private static final String BUILDS = "POST " + REPO_LINK + "/builds";

    private static final Set<String> ACCOUNT_SCOPED = Set.of(
            "POST " + GITHUB + "/authorizations",
            "POST " + GITHUB + "/authorizations/complete",
            "GET " + GITHUB + "/session",
            "DELETE " + GITHUB + "/session",
            "GET " + GITHUB + "/installations");
    private static final Set<String> ORG_SCOPED = Set.of(
            "POST " + INSTALLATIONS + "/install-sessions",
            "POST " + INSTALLATIONS + "/installations",
            "GET " + INSTALLATIONS + "/installations",
            "DELETE " + INSTALLATIONS + "/installations/{installationId}",
            "GET " + INSTALLATIONS + "/installations/{installationId}/repositories",
            PUT_REPO_LINK,
            "GET " + REPO_LINK,
            "PATCH " + REPO_LINK,
            "POST " + REPO_LINK + "/verification",
            "DELETE " + REPO_LINK,
            BUILDS);
    private static final Set<String> NEEDS_SESSION = Set.of(
            "GET " + GITHUB + "/installations",
            "POST " + INSTALLATIONS + "/installations",
            "GET " + INSTALLATIONS + "/installations/{installationId}/repositories",
            PUT_REPO_LINK,
            "POST " + REPO_LINK + "/verification");
    private static final Set<String> CREDENTIAL_SHAPED =
            Set.of("token", "accesstoken", "refreshtoken", "privatekey", "secret");
    private static final Set<String> ENTITIES = Set.of(
            "RepoLink",
            "Installation",
            "InstallationLink",
            "InstallationRepositoryEntry",
            "WebhookDelivery",
            "ManualBuildRequest",
            "BranchHead",
            "CheckRun",
            "AppProjection",
            "MembershipProjection",
            "GitHubUserSession");

    private static JsonNode spec;

    @Autowired
    private MockMvc mvc;

    @BeforeAll
    static void loadSpec(@Autowired MockMvc mvc) throws Exception {
        String body = mvc.perform(get(DOCS))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        spec = JsonMapper.builder().build().readTree(body);
    }

    @Test
    void theDocsAreReachableWithNoToken() throws Exception {
        mvc.perform(get(DOCS)).andExpect(status().isOk());
    }

    @Test
    void theSpecListsExactlyTheApiOperations() {
        Set<String> expected = new HashSet<>(ACCOUNT_SCOPED);
        expected.addAll(ORG_SCOPED);
        expected.add(WEBHOOK);

        assertThat(operations().keySet()).isEqualTo(expected);
    }

    @Test
    void theOperationsFallUnderFourTags() {
        Set<String> tags = new HashSet<>();
        operations().values().forEach(operation -> operation.path("tags").forEach(tag -> tags.add(tag.asString())));

        assertThat(tags).containsExactlyInAnyOrder("Webhooks", "GitHub session", "Installations", "Repository links");
    }

    @Test
    void everyOperationHasASummaryAndTheStandardErrorResponses() {
        operations().forEach((key, operation) -> {
            assertThat(operation.path("summary").asString("")).as(key).isNotBlank();
            assertThat(responseCodes(operation)).as(key).containsAll(STANDARD_ERRORS);
        });
    }

    @Test
    void everyOperationDocumentsTheSuccessItAnswers() {
        operations().forEach((key, operation) -> {
            List<String> success = responseCodes(operation).stream()
                    .filter(code -> code.startsWith("2"))
                    .toList();
            assertThat(success).as(key).isNotEmpty();
            success.stream()
                    .filter(code -> !code.equals("204"))
                    .forEach(code -> assertThat(operation.at("/responses/" + code + "/content"))
                            .as(key + " " + code)
                            .isNotEmpty());
        });
        assertThat(responseCodes(operations().get("POST " + GITHUB + "/authorizations")))
                .contains("201");
        assertThat(responseCodes(operations().get("POST " + INSTALLATIONS + "/install-sessions")))
                .contains("201");
    }

    @Test
    void pagedOperationsTakePageSizeAndSortAsQueryParameters() {
        for (String key : List.of(
                "GET " + GITHUB + "/installations",
                "GET " + INSTALLATIONS + "/installations",
                "GET " + INSTALLATIONS + "/installations/{installationId}/repositories")) {
            JsonNode operation = operations().get(key);
            for (String name : List.of("page", "size", "sort")) {
                JsonNode parameter = parameter(operation, name);
                assertThat(parameter).as(key + " " + name).isNotNull();
                assertThat(parameter.path("in").asString()).as(key + " " + name).isEqualTo("query");
            }
            assertThat(parameter(operation, "pageQuery")).as(key).isNull();
        }
    }

    @Test
    void onlyTheWebhookOptsOutOfBearerAuth() {
        assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asString())
                .isEqualTo("bearer");
        assertThat(spec.at("/security/0/bearerAuth").isArray()).isTrue();

        JsonNode webhookSecurity = operations().get(WEBHOOK).path("security");
        assertThat(webhookSecurity.isArray()).isTrue();
        assertThat(webhookSecurity).isEmpty();
        operations().forEach((key, operation) -> {
            if (!key.equals(WEBHOOK)) {
                assertThat(operation.has("security")).as(key).isFalse();
            }
        });
    }

    @Test
    void everyOperationStatesItsRoleFloorInTheSharedWording() {
        operations().forEach((key, operation) -> {
            String description = operation.path("description").asString("");
            if (ORG_SCOPED.contains(key)) {
                assertThat(description).as(key).containsAnyOf(ApiDocs.ANY_MEMBER, ApiDocs.DEVELOPER, ApiDocs.ADMIN);
            } else if (ACCOUNT_SCOPED.contains(key)) {
                assertThat(description).as(key).contains(ApiDocs.ANY_ACCOUNT);
            } else {
                assertThat(description).as(key).contains("Called by GitHub only");
            }
        });
    }

    @Test
    void everyOrgOperationStatesWhetherItNeedsAGitHubSession() {
        operations().forEach((key, operation) -> {
            String description = operation.path("description").asString("");
            if (NEEDS_SESSION.contains(key)) {
                assertThat(description).as(key).contains(ApiDocs.SESSION.strip());
                assertThat(operation.at("/responses/403/description").asString())
                        .as(key)
                        .contains("GITHUB_AUTHORIZATION_REQUIRED");
            } else if (ORG_SCOPED.contains(key)) {
                assertThat(description).as(key).contains(ApiDocs.NO_SESSION.strip());
            }
        });
    }

    @Test
    void linkingARepositoryDocumentsItsDomainErrorsAndTheDeployFlags() {
        JsonNode link = operations().get(PUT_REPO_LINK);

        assertThat(link.at("/responses/403/description").asString())
                .contains("REPOSITORY_NOT_ACCESSIBLE", "REPOSITORY_PERMISSION_TOO_LOW");
        assertThat(link.at("/responses/422/description").asString())
                .contains("REPOSITORY_ARCHIVED", "BRANCH_NOT_FOUND");
        assertThat(link.at("/responses/409/description").asString())
                .contains("REPO_LINK_EXISTS", "INSTALLATION_SUSPENDED");
        assertThat(link.at("/responses/400/description").asString()).contains("INVALID_ROOT_DIRECTORY");
        assertThat(link.path("description").asString())
                .contains("autoDeploy says whether future pushes build, deployNow whether to build the current head");
    }

    @Test
    void manualBuildsDocumentTheIdempotencyKeyAndTheirLimits() {
        JsonNode build = operations().get(BUILDS);

        JsonNode header = parameter(build, "Idempotency-Key");
        assertThat(header).isNotNull();
        assertThat(header.path("in").asString()).isEqualTo("header");
        assertThat(header.path("required").asBoolean()).isTrue();
        assertThat(build.path("description").asString()).contains("returns the first result");
        assertThat(build.at("/responses/429/description").asString()).contains("TOO_MANY_REQUESTS", "retryAfter");
        assertThat(build.at("/responses/409/description").asString()).contains("IDEMPOTENCY_KEY_REUSE");
        assertThat(build.at("/responses/503/description").asString()).contains("GITHUB_RATE_LIMITED");
    }

    @Test
    void theWebhookDocumentsGitHubsHeadersAndEveryStatusItAnswers() {
        JsonNode webhook = operations().get(WEBHOOK);

        for (String name : List.of("X-Hub-Signature-256", "X-GitHub-Event", "X-GitHub-Delivery")) {
            JsonNode header = parameter(webhook, name);
            assertThat(header).as(name).isNotNull();
            assertThat(header.path("in").asString()).as(name).isEqualTo("header");
            assertThat(header.path("required").asBoolean()).as(name).isTrue();
        }
        assertThat(responseCodes(webhook)).contains("202", "200", "400", "401", "413", "415", "503");
        assertThat(webhook.at("/responses/401/description").asString()).contains("WEBHOOK_SIGNATURE_INVALID");
        assertThat(webhook.at("/externalDocs/url").asString()).startsWith("https://docs.github.com/");
    }

    @Test
    void installationOperationsDocumentTheFlowsTheDashboardFollows() {
        String tag = "";
        for (JsonNode candidate : spec.path("tags")) {
            if (candidate.path("name").asString().equals("Installations")) {
                tag = candidate.path("description").asString();
            }
        }
        assertThat(tag).contains("setup_action=request", "nothing to call", "{installationId} alone");
        assertThat(operations()
                        .get("GET " + INSTALLATIONS + "/installations/{installationId}/repositories")
                        .path("description")
                        .asString())
                .contains("never the installation's full list");
        assertThat(operations()
                        .get("POST " + INSTALLATIONS + "/installations")
                        .at("/responses/403/description")
                        .asString())
                .contains("INSTALLATION_NOT_ACCESSIBLE");
    }

    @Test
    void requestSchemasCarryTheirConstraints() {
        JsonNode put = spec.at("/components/schemas/PutRepoLinkRequest/properties");

        assertThat(put.at("/productionBranch/pattern").asString()).isEqualTo(ApiDocs.BRANCH_PATTERN);
        assertThat(put.at("/rootDirectory/pattern").asString()).isEqualTo(ApiDocs.ROOT_DIRECTORY_PATTERN);
        assertThat(spec.at("/components/schemas/PatchRepoLinkRequest/properties/productionBranch/pattern")
                        .asString())
                .isEqualTo(ApiDocs.BRANCH_PATTERN);
        assertThat(spec.at("/components/schemas/ManualBuildRequestDto/properties/branch/pattern")
                        .asString())
                .isEqualTo(ApiDocs.BRANCH_PATTERN);
        List<String> permissions = new ArrayList<>();
        spec.at("/components/schemas/PickerRepositoryResponse/properties/permission/enum")
                .forEach(value -> permissions.add(value.asString()));
        assertThat(permissions).contains("admin", "maintain", "push");
    }

    @Test
    void noSchemaExposesACredentialOrAnEntity() {
        Set<String> leaked = new HashSet<>();
        spec.at("/components/schemas").properties().forEach(schema -> {
            if (ENTITIES.contains(schema.getKey())) {
                leaked.add(schema.getKey());
            }
            schema.getValue().path("properties").propertyNames().forEach(property -> {
                if (CREDENTIAL_SHAPED.contains(property.toLowerCase(Locale.ROOT))) {
                    leaked.add(schema.getKey() + "." + property);
                }
            });
        });

        assertThat(leaked).isEmpty();
        assertThat(spec.at("/components/schemas/GitHubSessionResponse/description")
                        .asString())
                .contains("Never returned");
    }

    private static Map<String, JsonNode> operations() {
        Map<String, JsonNode> operations = new LinkedHashMap<>();
        spec.path("paths")
                .properties()
                .forEach(path -> path.getValue().properties().forEach(method -> {
                    if (HTTP_METHODS.contains(method.getKey())) {
                        operations.put(
                                method.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey(), method.getValue());
                    }
                }));
        return operations;
    }

    private static List<String> responseCodes(JsonNode operation) {
        List<String> codes = new ArrayList<>();
        operation.path("responses").propertyNames().forEach(codes::add);
        return codes;
    }

    private static JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (parameter.path("name").asString("").equals(name)) {
                return parameter;
            }
        }
        return null;
    }
}
