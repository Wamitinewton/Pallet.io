package io.pallet.orgteam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.jayway.jsonpath.JsonPath;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.OrgFixtures;
import io.pallet.orgteam.security.OrgTeamTestTokens;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class SecurityHardeningIntegrationTest {

    private static final String PREVIEW_PATTERN = "/api/v1/org-team/invites/{token}";
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");
    private static final int FORGED_TOKEN_BURST = 250;

    private static final Map<String, String> VALID_BODIES = Map.of(
            "PATCH /api/v1/org-team/orgs/{orgId}", "{\"name\":\"Renamed\"}",
            "POST /api/v1/org-team/orgs/{orgId}/teams", "{\"name\":\"Platform\"}",
            "PATCH /api/v1/org-team/orgs/{orgId}/teams/{teamId}", "{\"name\":\"Platform\"}",
            "POST /api/v1/org-team/orgs/{orgId}/teams/{teamId}/members", "{\"userId\":\"someone\"}",
            "POST /api/v1/org-team/orgs/{orgId}/invites", "{\"email\":\"a@example.com\",\"role\":\"VIEWER\"}",
            "POST /api/v1/org-team/orgs/{orgId}/apps",
                    "{\"name\":\"Web\",\"cloudProvider\":\"AWS\",\"region\":\"us-east-1\"}",
            "PATCH /api/v1/org-team/orgs/{orgId}/apps/{appId}", "{\"name\":\"Web\"}",
            "PATCH /api/v1/org-team/orgs/{orgId}/members/{userId}", "{\"role\":\"VIEWER\"}");

    private static final List<String> LEAK_MARKERS = List.of(
            "Exception",
            "org.springframework",
            "io.pallet",
            "java.lang",
            "at io.",
            "stackTrace",
            "org_team.",
            "SELECT ",
            "INSERT ",
            "hibernate",
            "postgres",
            "Bearer ",
            "eyJ");

    private record Endpoint(String method, String pattern) {

        boolean hasBody() {
            return VALID_BODIES.containsKey(key());
        }

        String key() {
            return method + " " + pattern;
        }

        boolean tenantScoped() {
            return pattern.contains("{orgId}");
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private OrgFixtures fixtures;
    private final List<String> orgIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new OrgFixtures(jdbc);
    }

    @AfterEach
    void cleanUp() {
        orgIds.forEach(orgId -> jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId));
        fixtures.cleanUp();
        orgIds.clear();
    }

    private record Tenant(String orgId, String owner) {}

    private Tenant newTenant() {
        String orgId = fixtures.newActiveOrg();
        orgIds.add(orgId);
        String owner = fixtures.newMember(orgId, "OWNER", "ACTIVE");
        jdbc.update("UPDATE org_team.organizations SET owner_user_id = ? WHERE org_id = ?", owner, orgId);
        return new Tenant(orgId, owner);
    }

    private List<Endpoint> endpoints() {
        List<Endpoint> found = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("io.pallet.orgteam")
                    || info.getPathPatternsCondition() == null) {
                return;
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    found.add(new Endpoint(method.name(), pattern));
                }
            }
        });
        return found;
    }

    private MockHttpServletRequestBuilder build(Endpoint endpoint, Map<String, String> variables, String... fallback) {
        String path = endpoint.pattern();
        var matcher = PATH_VARIABLE.matcher(path);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String value = variables.getOrDefault(matcher.group(1), fallback.length > 0 ? fallback[0] : "x");
            matcher.appendReplacement(
                    resolved, Pattern.quote(value).replace("\\Q", "").replace("\\E", ""));
        }
        matcher.appendTail(resolved);
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(endpoint.method()), resolved.toString());
        if (endpoint.hasBody()) {
            builder.contentType(MediaType.APPLICATION_JSON).content(VALID_BODIES.get(endpoint.key()));
        }
        return builder;
    }

    private static String bearer(Tenant tenant) {
        return "Bearer "
                + OrgTeamTestTokens.forMember(tenant.orgId(), tenant.owner()).signed();
    }

    @Test
    void theEndpointEnumerationCoversTheWholeApi() {
        assertThat(endpoints()).hasSizeGreaterThanOrEqualTo(25);
        assertThat(endpoints()).extracting(Endpoint::pattern).contains(PREVIEW_PATTERN);
    }

    @Test
    void everyEndpointButThePreviewIsUnauthorizedWithoutAToken() throws Exception {
        for (Endpoint endpoint : endpoints()) {
            MvcResult result = mvc.perform(build(endpoint, Map.of())).andReturn();
            int status = result.getResponse().getStatus();
            if (endpoint.pattern().equals(PREVIEW_PATTERN)) {
                assertThat(status).as(endpoint.key()).isNotEqualTo(401);
            } else {
                assertThat(status).as(endpoint.key()).isEqualTo(401);
            }
        }
    }

    @Test
    void onlyTheProbesAreExposedByTheActuator() throws Exception {
        for (String hidden : List.of(
                "env",
                "beans",
                "heapdump",
                "threaddump",
                "mappings",
                "configprops",
                "loggers",
                "metrics",
                "shutdown")) {
            int status = mvc.perform(get("/actuator/" + hidden))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).as("/actuator/" + hidden).isIn(401, 403, 404);
        }
        assertThat(mvc.perform(get("/actuator/health/liveness"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get("/actuator/health/readiness"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        MvcResult index = mvc.perform(get("/actuator")).andReturn();
        if (index.getResponse().getStatus() == 200) {
            Map<String, Object> links = JsonPath.read(index.getResponse().getContentAsString(), "$._links");
            assertThat(links.keySet()).isSubsetOf("self", "health", "health-path", "info", "prometheus");
        }
    }

    @Test
    void securityHeadersAccompanyPublicAndAuthenticatedResponsesAlike() throws Exception {
        Tenant tenant = newTenant();
        List<MvcResult> responses = List.of(
                mvc.perform(get("/api/v1/org-team/orgs/{orgId}", tenant.orgId())
                                .header("Authorization", bearer(tenant)))
                        .andReturn(),
                mvc.perform(get("/api/v1/org-team/invites/{token}", "forged")).andReturn(),
                mvc.perform(get("/api/v1/org-team/orgs/{orgId}", tenant.orgId()))
                        .andReturn(),
                mvc.perform(get("/api/v1/org-team/nothing-here")).andReturn(),
                mvc.perform(get("/actuator/health/liveness")).andReturn());

        for (MvcResult result : responses) {
            String path = result.getRequest().getRequestURI();
            assertThat(result.getResponse().getHeader("X-Content-Type-Options"))
                    .as(path)
                    .isEqualTo("nosniff");
        }
        for (MvcResult result : responses.subList(0, 4)) {
            assertThat(result.getResponse().getHeader("Cache-Control"))
                    .as(result.getRequest().getRequestURI())
                    .contains("no-store");
        }
    }

    @Test
    void aBurstOfForgedPreviewTokensRunsNoSql() throws Exception {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        long before = sessionFactory.getStatistics().getPrepareStatementCount();

        for (int i = 0; i < FORGED_TOKEN_BURST; i++) {
            int status = mvc.perform(get("/api/v1/org-team/invites/{token}", "forged." + i + ".token"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).isEqualTo(400);
        }

        assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(before);
    }

    @Test
    void everyRequestDtoRejectsAnUnknownField() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*\\.[A-Za-z]+Request")));
        Set<BeanDefinition> requests = scanner.findCandidateComponents("io.pallet.orgteam");

        assertThat(requests).hasSizeGreaterThanOrEqualTo(6);
        for (BeanDefinition definition : requests) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            String json = "{\"__notAField\":\"x\",\"role\":\"OWNER\",\"cloudProvider\":\"GCP\",\"slug\":\"y\"}";
            Throwable failure = null;
            try {
                jsonMapper.readValue(json, type);
            } catch (RuntimeException e) {
                failure = e;
            }
            assertThat(failure)
                    .as(type.getSimpleName())
                    .isInstanceOf(DatabindException.class)
                    .hasMessageMatching("(?is).*(unknown|unrecognized).*");
        }
    }

    @Test
    void malformedInputNeverProducesAServerErrorOrLeaksInternals() throws Exception {
        Tenant tenant = newTenant();
        for (Endpoint endpoint : endpoints()) {
            if (endpoint.pattern().equals(PREVIEW_PATTERN)) {
                continue;
            }
            Map<String, String> variables = Map.of("orgId", tenant.orgId());
            MockHttpServletRequestBuilder malformed = build(endpoint, variables, "not-a-valid-id")
                    .header("Authorization", bearer(tenant))
                    .header("X-Confirm-Slug", "wrong-slug");
            if (endpoint.hasBody()) {
                malformed.content("{\"broken\":");
            }
            assertSafeError(endpoint, mvc.perform(malformed).andReturn());
        }
    }

    private void assertSafeError(Endpoint endpoint, MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        assertThat(status).as(endpoint.key() + " -> " + body).isLessThan(500);
        if (status < 400) {
            return;
        }
        assertThat((Boolean) JsonPath.read(body, "$.success"))
                .as(endpoint.key())
                .isFalse();
        assertThat((String) JsonPath.read(body, "$.error")).as(endpoint.key()).isNotBlank();
        for (String marker : LEAK_MARKERS) {
            assertThat(body).as(endpoint.key() + " leaks " + marker).doesNotContain(marker);
        }
    }

    @Test
    void aTokenFromAnotherOrgIsNotFoundOnEveryTenantScopedEndpoint() throws Exception {
        Tenant caller = newTenant();
        Tenant victim = newTenant();
        String unknownBody = mvc.perform(get("/api/v1/org-team/orgs/{orgId}", "org-does-not-exist")
                        .header("Authorization", bearer(caller)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        int checked = 0;
        for (Endpoint endpoint : endpoints()) {
            if (!endpoint.tenantScoped()) {
                continue;
            }
            Map<String, String> variables = Map.of(
                    "orgId", victim.orgId(),
                    "teamId", UUID.randomUUID().toString(),
                    "appId", UUID.randomUUID().toString(),
                    "inviteId", UUID.randomUUID().toString());
            MockHttpServletRequestBuilder attempt = build(endpoint, variables)
                    .header("Authorization", bearer(caller))
                    .header("X-Confirm-Slug", victim.orgId());
            MvcResult result = mvc.perform(attempt).andReturn();
            String body = result.getResponse().getContentAsString();

            assertThat(result.getResponse().getStatus())
                    .as(endpoint.key() + " -> " + body)
                    .isEqualTo(404);
            assertThat((String) JsonPath.read(body, "$.error"))
                    .as(endpoint.key())
                    .isEqualTo("ORG_NOT_FOUND");
            assertThat((String) JsonPath.read(body, "$.message"))
                    .as(endpoint.key())
                    .isEqualTo(JsonPath.read(unknownBody, "$.message"));
            checked++;
        }

        assertThat(checked).isGreaterThanOrEqualTo(20);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM org_team.organizations WHERE org_id = ?", String.class, victim.orgId()))
                .isEqualTo("ACTIVE");
    }
}
