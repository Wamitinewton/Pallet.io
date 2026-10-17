package io.pallet.buildqueue.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.buildqueue.api.ApiDocs;
import io.pallet.buildqueue.api.dto.BuildDto;
import io.pallet.buildqueue.api.dto.QueueUsageDto;
import io.pallet.buildqueue.api.dto.WaitingOn;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

    private static final String BASE = "/api/v1/build-queue";
    private static final String DOCS = BASE + "/v3/api-docs";
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
    private static final List<String> STANDARD_ERRORS = List.of("400", "401", "403", "404", "409", "429", "500");

    private static final String ORG = BASE + "/orgs/{orgId}";
    private static final String QUEUE = "GET " + ORG + "/queue";
    private static final String HISTORY = "GET " + ORG + "/apps/{appId}/builds";
    private static final String BUILD = "GET " + ORG + "/builds/{buildId}";
    private static final String CANCEL = "POST " + ORG + "/builds/{buildId}/cancel";

    private static final Map<String, String> TAGS =
            Map.of(QUEUE, "Queue", HISTORY, "Builds", BUILD, "Builds", CANCEL, "Builds");
    private static final Map<String, Map<String, List<String>>> DOMAIN_ERRORS = Map.of(
            QUEUE,
            Map.of("403", List.of("NOT_A_MEMBER", "INSUFFICIENT_ROLE"), "404", List.of("ORG_NOT_FOUND")),
            HISTORY,
            Map.of(
                    "403",
                    List.of("NOT_A_MEMBER", "INSUFFICIENT_ROLE"),
                    "404",
                    List.of("ORG_NOT_FOUND", "APP_NOT_FOUND")),
            BUILD,
            Map.of(
                    "403", List.of("NOT_A_MEMBER", "INSUFFICIENT_ROLE"),
                    "404", List.of("ORG_NOT_FOUND", "BUILD_NOT_FOUND")),
            CANCEL,
            Map.of(
                    "403", List.of("NOT_A_MEMBER", "INSUFFICIENT_ROLE"),
                    "404", List.of("ORG_NOT_FOUND", "BUILD_NOT_FOUND"),
                    "409", List.of("BUILD_NOT_CANCELLABLE", "meta.status")));
    private static final Set<String> ENTITIES =
            Set.of("Build", "AppProjection", "OrgProjection", "MembershipProjection");

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
        assertThat(operations().keySet()).containsExactlyInAnyOrderElementsOf(TAGS.keySet());
    }

    @Test
    void eachOperationFallsUnderItsTag() {
        operations()
                .forEach((key, operation) -> assertThat(operation.path("tags"))
                        .as(key)
                        .extracting(JsonNode::asString)
                        .containsExactly(TAGS.get(key)));
        assertThat(spec.path("tags"))
                .extracting(tag -> tag.path("name").asString())
                .contains("Queue", "Builds");
    }

    @Test
    void everyOperationHasASummaryAndTheStandardErrorResponses() {
        operations().forEach((key, operation) -> {
            assertThat(operation.path("summary").asString("")).as(key).isNotBlank();
            assertThat(responseCodes(operation)).as(key).containsAll(STANDARD_ERRORS);
        });
    }

    @Test
    void everyOperationDocumentsTheBodyOfItsSuccess() {
        operations().forEach((key, operation) -> {
            assertThat(responseCodes(operation)).as(key).contains("200");
            assertThat(operation.at("/responses/200/content")).as(key).isNotEmpty();
        });
    }

    @Test
    void everyOperationNeedsABearerToken() {
        assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asString())
                .isEqualTo("bearer");
        assertThat(spec.at("/security/0/bearerAuth").isArray()).isTrue();
        operations()
                .forEach((key, operation) ->
                        assertThat(operation.has("security")).as(key).isFalse());
    }

    @Test
    void everyOperationStatesItsRoleFloorInTheSharedWording() {
        operations()
                .forEach((key, operation) -> assertThat(
                                operation.path("description").asString(""))
                        .as(key)
                        .contains(key.equals(CANCEL) ? ApiDocs.DEVELOPER : ApiDocs.ANY_MEMBER));
    }

    @Test
    void everyOperationDocumentsItsDomainErrorCodes() {
        DOMAIN_ERRORS.forEach((key, errors) -> errors.forEach((code, expected) -> {
            JsonNode response = operations().get(key).at("/responses/" + code);
            assertThat(response.path("description").asString())
                    .as(key + " " + code)
                    .contains(expected);
            assertThat(response.path("content"))
                    .as(key + " " + code)
                    .isNotEmpty()
                    .allSatisfy(media ->
                            assertThat(media.at("/schema/$ref").asString()).endsWith("/ErrorResponse"));
        }));
        assertThat(operations().get(HISTORY).at("/responses/400/description").asString())
                .contains("unknown status");
    }

    @Test
    void pagedOperationsTakePageSizeAndSortAsQueryParametersAndSayTheOrderIsFixed() {
        for (String key : List.of(QUEUE, HISTORY)) {
            JsonNode operation = operations().get(key);
            for (String name : List.of("page", "size", "sort")) {
                JsonNode parameter = parameter(operation, name);
                assertThat(parameter).as(key + " " + name).isNotNull();
                assertThat(parameter.path("in").asString()).as(key + " " + name).isEqualTo("query");
            }
            assertThat(parameter(operation, "pageQuery")).as(key).isNull();
            assertThat(operation.path("description").asString()).as(key).contains("sort is ignored");
        }
    }

    @Test
    void theStatusEnumListsExactlyTheStatusesInTheCodeAndExplainsEachOne() {
        JsonNode status = schema("BuildDto").at("/properties/status");

        assertThat(values(status.path("enum"))).containsExactlyInAnyOrderElementsOf(names(BuildStatus.values()));
        assertThat(status.path("description").asString())
                .contains(names(BuildStatus.values()).stream()
                        .map(name -> name + ":")
                        .toList())
                .contains("newer commit", "may still report a result");
    }

    @Test
    void theHistoryStatusFilterListsExactlyTheStatusesInTheCode() {
        JsonNode filter = parameter(operations().get(HISTORY), "status");

        assertThat(filter).isNotNull();
        assertThat(filter.path("in").asString()).isEqualTo("query");
        assertThat(values(filter.at("/schema/items/enum")))
                .containsExactlyInAnyOrderElementsOf(names(BuildStatus.values()));
    }

    @Test
    void theWaitingOnEnumListsExactlyTheReasonsInTheCodeAndWhatToDoAboutEach() {
        JsonNode waitingOn = schema("QueueEntryDto").at("/properties/waitingOn");

        assertThat(values(waitingOn.path("enum"))).containsExactlyInAnyOrderElementsOf(names(WaitingOn.values()));
        assertThat(waitingOn.path("description").asString())
                .contains(names(WaitingOn.values()).stream()
                        .map(name -> name + ":")
                        .toList());
    }

    @Test
    void theTriggerValuesAreExactlyThePushTriggers() throws IllegalAccessException {
        Set<String> triggers = new HashSet<>();
        for (Field field : GitPushReceived.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("TRIGGER_")) {
                triggers.add((String) field.get(null));
            }
        }

        assertThat(values(schema("BuildDto").at("/properties/trigger/enum")))
                .containsExactlyInAnyOrderElementsOf(triggers);
    }

    @Test
    void theQueueViewTellsTheDashboardHowToReadIt() {
        String queue = operations().get(QUEUE).path("description").asString();

        assertThat(queue)
                .contains("in flight first", "1-based", "across pages", "poll it")
                .contains(names(WaitingOn.values()));
        assertThat(schema("QueueViewDto").path("description").asString()).contains("poll it");
        assertThat(operations().get(CANCEL).path("description").asString())
                .contains("Idempotent", "Only a waiting build", "can't be cancelled yet");
        assertThat(tagDescription("Builds")).contains("no endpoint to enqueue a build");
    }

    @Test
    void schemasCarryExactlyTheDtoFieldsAndNoEntity() {
        Set<String> build = components(BuildDto.class);
        Set<String> entry = new HashSet<>(build);
        entry.addAll(Set.of("position", "waitingOn"));

        assertThat(propertyNames(schema("BuildDto"))).isEqualTo(build);
        assertThat(propertyNames(schema("QueueEntryDto"))).isEqualTo(entry);
        assertThat(propertyNames(schema("QueueUsageDto"))).isEqualTo(components(QueueUsageDto.class));
        assertThat(propertyNames(spec.at("/components/schemas"))).doesNotContainAnyElementsOf(ENTITIES);
        assertThat(schema("QueueEntryDto")
                        .at("/properties/position/description")
                        .asString())
                .contains("null for a build in flight");
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

    private static JsonNode schema(String name) {
        JsonNode schema = spec.at("/components/schemas/" + name);
        assertThat(schema.isMissingNode()).as(name).isFalse();
        return schema;
    }

    private static String tagDescription(String name) {
        for (JsonNode tag : spec.path("tags")) {
            if (tag.path("name").asString().equals(name)) {
                return tag.path("description").asString();
            }
        }
        return "";
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

    private static Set<String> propertyNames(JsonNode schema) {
        Set<String> names = new HashSet<>();
        (schema.has("properties") ? schema.path("properties") : schema)
                .propertyNames()
                .forEach(names::add);
        return names;
    }

    private static List<String> values(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    private static List<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).toList();
    }

    private static Set<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }
}
