package io.pallet.orgteam.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
class OpenApiIntegrationTest {

    private static final String BASE = "/api/v1/org-team";
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "patch", "delete", "put");
    private static final List<String> STANDARD_ERRORS = List.of("400", "401", "403", "404", "409", "429", "500");
    private static final Set<String> EXPECTED_OPERATIONS = Set.of(
            "GET " + BASE + "/orgs/{orgId}",
            "PATCH " + BASE + "/orgs/{orgId}",
            "DELETE " + BASE + "/orgs/{orgId}",
            "GET " + BASE + "/orgs/{orgId}/members",
            "GET " + BASE + "/orgs/{orgId}/members/me",
            "GET " + BASE + "/orgs/{orgId}/members/{userId}",
            "PATCH " + BASE + "/orgs/{orgId}/members/{userId}",
            "DELETE " + BASE + "/orgs/{orgId}/members/{userId}",
            "POST " + BASE + "/orgs/{orgId}/members/{userId}/transfer-ownership",
            "POST " + BASE + "/orgs/{orgId}/invites",
            "GET " + BASE + "/orgs/{orgId}/invites",
            "POST " + BASE + "/orgs/{orgId}/invites/{inviteId}/resend",
            "DELETE " + BASE + "/orgs/{orgId}/invites/{inviteId}",
            "GET " + BASE + "/invites/{token}",
            "POST " + BASE + "/orgs/{orgId}/teams",
            "GET " + BASE + "/orgs/{orgId}/teams",
            "GET " + BASE + "/orgs/{orgId}/teams/{teamId}",
            "PATCH " + BASE + "/orgs/{orgId}/teams/{teamId}",
            "DELETE " + BASE + "/orgs/{orgId}/teams/{teamId}",
            "GET " + BASE + "/orgs/{orgId}/teams/{teamId}/members",
            "POST " + BASE + "/orgs/{orgId}/teams/{teamId}/members",
            "DELETE " + BASE + "/orgs/{orgId}/teams/{teamId}/members/{userId}",
            "POST " + BASE + "/orgs/{orgId}/apps",
            "GET " + BASE + "/orgs/{orgId}/apps",
            "GET " + BASE + "/orgs/{orgId}/apps/{appId}",
            "PATCH " + BASE + "/orgs/{orgId}/apps/{appId}",
            "DELETE " + BASE + "/orgs/{orgId}/apps/{appId}");

    private static final String PREVIEW = "GET " + BASE + "/invites/{token}";

    @Autowired
    private MockMvc mvc;

    private static JsonNode spec;

    @BeforeAll
    static void loadSpec(@Autowired MockMvc mvc) throws Exception {
        String body = mvc.perform(get(BASE + "/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        spec = JsonMapper.builder().build().readTree(body);
    }

    @Test
    void theDocsAreReachableWithNoToken() throws Exception {
        mvc.perform(get(BASE + "/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void theSpecListsExactlyTheControllerOperationsUnderTheNamespace() {
        assertThat(operations().keySet()).isEqualTo(EXPECTED_OPERATIONS);
    }

    @Test
    void everyOperationHasASummaryAndTheStandardErrorResponses() {
        operations().forEach((key, operation) -> {
            assertThat(operation.path("summary").asString(""))
                    .as(key + " summary")
                    .isNotBlank();
            List<String> documented = new ArrayList<>();
            operation.path("responses").propertyNames().forEach(documented::add);
            assertThat(documented).as(key + " responses").containsAll(STANDARD_ERRORS);
        });
    }

    @Test
    void everyOperationSaysWhichRoleItNeedsExceptThePublicPreview() {
        operations().forEach((key, operation) -> {
            if (key.equals(PREVIEW)) {
                return;
            }
            assertThat(operation.path("description").asString(""))
                    .as(key + " description")
                    .contains("judged from your current membership, not your token's roles");
        });
    }

    @Test
    void bearerAuthIsRegisteredAndOnlyThePreviewOptsOutOfIt() {
        assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asString())
                .isEqualTo("bearer");
        assertThat(spec.at("/security/0/bearerAuth").isArray()).isTrue();

        JsonNode previewSecurity = operations().get(PREVIEW).path("security");
        assertThat(previewSecurity.isArray()).isTrue();
        assertThat(previewSecurity).isEmpty();
        operations().forEach((key, operation) -> {
            if (!key.equals(PREVIEW)) {
                assertThat(operation.has("security")).as(key).isFalse();
            }
        });
    }

    @Test
    void noSchemaExposesATokenOrAnAcceptUrl() {
        Set<String> leaked = new HashSet<>();
        spec.at("/components/schemas")
                .properties()
                .forEach(schema -> schema.getValue()
                        .path("properties")
                        .propertyNames()
                        .forEach(property -> {
                            if (property.equals("token") || property.equals("acceptUrl")) {
                                leaked.add(schema.getKey() + "." + property);
                            }
                        }));

        assertThat(leaked).isEmpty();
        assertThat(spec.at("/components/schemas/InviteDto/description").asString())
                .contains("never part of any response");
    }

    @Test
    void placementFieldsAreOnCreateAppRequestAndNotOnUpdateAppRequest() {
        List<String> create = new ArrayList<>();
        spec.at("/components/schemas/CreateAppRequest/properties")
                .propertyNames()
                .forEach(create::add);
        List<String> update = new ArrayList<>();
        spec.at("/components/schemas/UpdateAppRequest/properties")
                .propertyNames()
                .forEach(update::add);

        assertThat(create).contains("cloudProvider", "region");
        assertThat(update).containsExactlyInAnyOrder("name", "teamId");
    }

    @Test
    void deletingAnOrgDocumentsTheConfirmationHeaderAndTheReauthenticationError() {
        JsonNode delete = operations().get("DELETE " + BASE + "/orgs/{orgId}");

        JsonNode header = null;
        for (JsonNode parameter : delete.path("parameters")) {
            if (parameter.path("name").asString("").equals("X-Confirm-Slug")) {
                header = parameter;
            }
        }
        assertThat(header).isNotNull();
        assertThat(header.path("in").asString()).isEqualTo("header");
        assertThat(delete.at("/responses/403/description").asString()).contains("REAUTHENTICATION_REQUIRED");
    }

    @Test
    void domainErrorCodesAreDocumentedWhereTheyApply() {
        assertThat(operations()
                        .get("POST " + BASE + "/orgs/{orgId}/invites")
                        .at("/responses/409/description")
                        .asString())
                .contains("ALREADY_A_MEMBER", "MEMBER_PREVIOUSLY_REMOVED", "INVITE_ALREADY_PENDING", "QUOTA_EXCEEDED");
        assertThat(operations().get(PREVIEW).at("/responses/410/description").asString())
                .contains("INVITE_NO_LONGER_VALID");
        assertThat(operations()
                        .get("POST " + BASE + "/orgs/{orgId}/members/{userId}/transfer-ownership")
                        .at("/responses/403/description")
                        .asString())
                .contains("REAUTHENTICATION_REQUIRED");
        assertThat(operations()
                        .get("POST " + BASE + "/orgs/{orgId}/apps")
                        .at("/responses/400/description")
                        .asString())
                .contains("INVALID_REGION");
    }

    @Test
    void revokingAnInviteExplainsThatAnEmailedLinkStillVerifies() {
        assertThat(operations()
                        .get("DELETE " + BASE + "/orgs/{orgId}/invites/{inviteId}")
                        .path("description")
                        .asString())
                .contains("A link already emailed still verifies cryptographically");
    }

    @Test
    void everyCreatingPostStatesThatARetryConflicts() {
        operations().forEach((key, operation) -> {
            if (key.startsWith("POST ") && !key.endsWith("/transfer-ownership")) {
                assertThat(operation.path("description").asString())
                        .as(key)
                        .contains("Retrying conflicts (409) rather than creating a duplicate.");
            }
        });
    }

    private java.util.Map<String, JsonNode> operations() {
        java.util.Map<String, JsonNode> operations = new java.util.LinkedHashMap<>();
        spec.path("paths")
                .properties()
                .forEach(path -> path.getValue().properties().forEach(method -> {
                    if (HTTP_METHODS.contains(method.getKey())) {
                        operations.put(method.getKey().toUpperCase() + " " + path.getKey(), method.getValue());
                    }
                }));
        return operations;
    }
}
