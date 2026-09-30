package io.pallet.gitintegration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.jayway.jsonpath.JsonPath;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.repolink.RepoLinkApi;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The threat model's rows that no feature test already owns (ARCHITECTURE.md §Security and threat model). */
@IntegrationTest
@AutoConfigureMockMvc
@Import({
    RedisTestContainerConfiguration.class,
    GitHubApiStub.Properties.class,
    Tokens.LocalDecoder.class,
    SecurityHardeningIntegrationTest.RequestThreadConnections.class
})
class SecurityHardeningIntegrationTest {

    private static final String API = "/api/v1/git-integration";
    private static final String WEBHOOK = API + "/webhooks/github";
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");
    private static final String GITHUB_BODY_MARKER = "github-body-marker-5f1c";
    private static final int FORGED_BURST = 200;
    private static final String HEAD = "a".repeat(40);

    private static final Map<String, String> VALID_BODIES = Map.of(
            "POST " + API + "/orgs/{orgId}/github/installations", "{\"installationId\":1}",
            "PUT " + API + "/orgs/{orgId}/apps/{appId}/repo-link", "{\"installationId\":1,\"repoId\":1}",
            "PATCH " + API + "/orgs/{orgId}/apps/{appId}/repo-link", "{\"autoDeploy\":false,\"version\":0}",
            "POST " + API + "/orgs/{orgId}/apps/{appId}/repo-link/builds", "{\"branch\":\"main\"}",
            "POST " + API + "/github/authorizations/complete", "{\"code\":\"abc\",\"state\":\"x.y\"}");

    private static final List<String> LEAK_MARKERS = List.of(
            "Exception", "org.springframework", "io.pallet", "java.lang", "git_integration.", "SELECT ", "Bearer ");

    private record Endpoint(String method, String pattern) {

        String key() {
            return method + " " + pattern;
        }
    }

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private GitIntegrationProperties properties;

    @Autowired
    private RequestThreadConnections connections;

    @Autowired
    @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;

    private ReadModelFixtures fixtures;
    private RepoLinkApi api;
    private final List<UUID> deliveries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        api = new RepoLinkApi(mvc, json, github);
    }

    @AfterEach
    void tearDown() {
        deliveries.forEach(
                id -> jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id));
        fixtures.cleanUp();
    }

    @Test
    void everyEndpointIsUnauthorizedWithoutATokenAndTheWebhookOnlyForItsSignature() throws Exception {
        List<Endpoint> endpoints = endpoints();
        assertThat(endpoints).hasSizeGreaterThanOrEqualTo(15);
        assertThat(endpoints).extracting(Endpoint::pattern).contains(WEBHOOK);
        for (Endpoint endpoint : endpoints) {
            MockHttpServletResponse response =
                    mvc.perform(build(endpoint)).andReturn().getResponse();
            assertThat(response.getStatus()).as(endpoint.key()).isEqualTo(401);
            if (endpoint.pattern().equals(WEBHOOK)) {
                assertThat(response.getContentAsString())
                        .as("refused by its signature, not for want of a bearer token")
                        .contains("WEBHOOK_SIGNATURE_INVALID");
            }
        }
    }

    @Test
    void theWebhookAnswersAMissingAndAWrongSignatureIdentically() throws Exception {
        WebhookFixtures.Delivery missing =
                WebhookFixtures.delivery("push-main.json").secret(null);
        WebhookFixtures.Delivery wrong =
                WebhookFixtures.delivery("push-main.json").secret("not-the-secret");

        MockHttpServletResponse unsigned = missing.post(mvc);
        MockHttpServletResponse forged = wrong.post(mvc);

        assertThat(unsigned.getStatus()).isEqualTo(401);
        assertThat(forged.getStatus()).isEqualTo(401);
        assertThat(withoutTimestamp(unsigned)).isEqualTo(withoutTimestamp(forged));
    }

    @Test
    void aBurstOfForgedSignaturesNeverTouchesTheDatabase() throws Exception {
        connections.watch(Thread.currentThread());
        try {
            for (int i = 0; i < FORGED_BURST; i++) {
                assertThat(WebhookFixtures.delivery("push-main.json")
                                .secret("forged-" + i)
                                .post(mvc)
                                .getStatus())
                        .isEqualTo(401);
            }
            assertThat(connections.borrowed()).isZero();

            WebhookFixtures.Delivery signed = WebhookFixtures.delivery("push-main.json");
            deliveries.add(UUID.fromString(signed.deliveryId()));
            assertThat(signed.post(mvc).getStatus()).isEqualTo(202);
            assertThat(connections.borrowed())
                    .as("the control: a signed delivery is stored")
                    .isPositive();
        } finally {
            connections.watch(null);
        }
    }

    @Test
    void onlyHealthAndPrometheusAreExposedByTheActuator() throws Exception {
        for (String hidden : List.of(
                "env",
                "beans",
                "heapdump",
                "threaddump",
                "mappings",
                "configprops",
                "loggers",
                "metrics",
                "shutdown",
                "refresh",
                "info",
                "scheduledtasks")) {
            assertThat(status(get("/actuator/" + hidden))).as(hidden).isIn(401, 403, 404);
            assertThat(status(post("/actuator/" + hidden))).as("POST " + hidden).isIn(401, 403, 404, 405);
        }
        assertThat(status(get("/actuator/health/liveness"))).isEqualTo(200);
        assertThat(status(get("/actuator/prometheus"))).isEqualTo(200);
        MvcResult index = mvc.perform(get("/actuator")).andReturn();
        if (index.getResponse().getStatus() == 200) {
            Map<String, Object> links = JsonPath.read(index.getResponse().getContentAsString(), "$._links");
            assertThat(links.keySet()).isSubsetOf("self", "health", "health-path", "prometheus");
        }
    }

    @Test
    void gitHubBaseUrlsCannotBeChangedWhileRunning() {
        URI api = properties.github().apiBaseUrl();
        URI web = properties.github().webBaseUrl();
        for (String name : context.getBeanNamesForType(GitIntegrationProperties.class)) {
            BeanDefinition definition = context.getBeanFactory().getBeanDefinition(name);
            assertThat(definition.getScope()).as(name).isNotEqualTo("refresh");
        }
        MapPropertySource hostile = new MapPropertySource(
                "hostile",
                Map.of(
                        "pallet.git.github.api-base-url", "http://169.254.169.254",
                        "pallet.git.github.web-base-url", "http://169.254.169.254"));
        ConfigurationPropertiesRebinder rebinder = context.getBean(ConfigurationPropertiesRebinder.class);
        context.getEnvironment().getPropertySources().addFirst(hostile);
        try {
            for (String name : context.getBeanNamesForType(GitIntegrationProperties.class)) {
                rebinder.rebind(name);
            }

            GitIntegrationProperties current = context.getBean(GitIntegrationProperties.class);
            assertThat(current.github().apiBaseUrl()).isEqualTo(api);
            assertThat(current.github().webBaseUrl()).isEqualTo(web);
        } finally {
            context.getEnvironment().getPropertySources().remove("hostile");
        }
    }

    @Test
    void securityHeadersMatchTheOtherServicesOnPublicAuthenticatedAndErrorResponses() throws Exception {
        String orgId = fixtures.newOrg();
        String member = fixtures.newMember(orgId, "developer", "ACTIVE");
        UUID appId = fixtures.newApp(orgId, "ACTIVE");
        List<MockHttpServletResponse> responses = List.of(
                api.as(member).get(orgId, appId),
                api.as("user-" + UUID.randomUUID()).get(orgId, appId),
                mvc.perform(get(API + "/github/session")).andReturn().getResponse(),
                WebhookFixtures.delivery("push-main.json").secret("forged").post(mvc),
                mvc.perform(get(API + "/nothing-here")).andReturn().getResponse());

        for (MockHttpServletResponse response : responses) {
            assertThat(response.getHeader("X-Content-Type-Options"))
                    .as(response.getStatus() + " " + response.getContentAsString())
                    .isEqualTo("nosniff");
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        }
    }

    @Test
    void everyRequestBodyRejectsAnUnknownPropertyAndPatchCannotRepointALink() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*\\.dto\\.[A-Za-z]+Request(Dto)?")));
        Set<BeanDefinition> requests = scanner.findCandidateComponents("io.pallet.gitintegration");
        assertThat(requests).hasSizeGreaterThanOrEqualTo(5);
        for (BeanDefinition definition : requests) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            Throwable failure = catchThrowable(() -> json.readValue("{\"__notAField\":\"x\"}", type));
            assertThat(failure).as(type.getSimpleName()).isNotNull();
        }

        String orgId = fixtures.newOrg();
        String admin = fixtures.newMember(orgId, "admin", "ACTIVE");
        long installationId = fixtures.newInstallation();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        long repoId = ThreadLocalRandom.current().nextLong(100_000, Long.MAX_VALUE);
        UUID appId = fixtures.newRepoLink(orgId, installationId, repoId);

        MockHttpServletResponse repointed =
                api.as(admin).update(orgId, appId, Map.of("repoId", repoId + 1, "autoDeploy", false, "version", 0));

        api.assertError(repointed, 400);
        assertThat(jdbc.queryForObject(
                        "SELECT repo_id FROM git_integration.repo_links WHERE app_id = ?", Long.class, appId))
                .isEqualTo(repoId);
    }

    @Test
    void noErrorBodyCarriesWhatGitHubAnswered() throws Exception {
        String orgId = fixtures.newOrg();
        String admin = fixtures.newMember(orgId, "admin", "ACTIVE");
        long installationId = fixtures.newInstallation();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        long repoId = ThreadLocalRandom.current().nextLong(100_000, Long.MAX_VALUE);
        UUID appId = fixtures.newRepoLink(orgId, installationId, repoId);
        github.stubTokenMint(installationId);
        List<MockHttpServletResponse> failures = new ArrayList<>();

        for (int status : new int[] {400, 403, 422, 500, 502}) {
            github.server()
                    .stubFor(any(urlPathEqualTo(GitHubApiStub.branchPath(repoId, "main")))
                            .willReturn(aResponse()
                                    .withStatus(status)
                                    .withHeader("Content-Type", "application/json")
                                    .withBody("{\"message\":\"" + GITHUB_BODY_MARKER + "\"}")));
            failures.add(api.as(admin).build(orgId, appId, "hardening-" + status, null));
        }
        github.server()
                .stubFor(any(urlPathEqualTo(GitHubApiStub.OAUTH_TOKEN_PATH))
                        .willReturn(aResponse().withStatus(500).withBody("<html>" + GITHUB_BODY_MARKER + "</html>")));
        failures.add(mvc.perform(post(API + "/github/authorizations/complete")
                        .header(
                                "Authorization",
                                "Bearer " + Tokens.forUser(admin).signed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"abc\",\"state\":\"forged.state\"}"))
                .andReturn()
                .getResponse());

        for (MockHttpServletResponse failure : failures) {
            String body = failure.getContentAsString();
            assertThat(failure.getStatus()).as(body).isGreaterThanOrEqualTo(400);
            assertThat(body).doesNotContain(GITHUB_BODY_MARKER);
            for (String marker : LEAK_MARKERS) {
                assertThat(body).as("leaks " + marker).doesNotContain(marker);
            }
        }
    }

    private List<Endpoint> endpoints() {
        List<Endpoint> found = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("io.pallet.gitintegration")
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

    private MockHttpServletRequestBuilder build(Endpoint endpoint) {
        String path = PATH_VARIABLE.matcher(endpoint.pattern()).replaceAll(variable -> switch (variable.group(1)) {
            case "appId" -> UUID.randomUUID().toString();
            case "installationId" -> "1";
            default -> "org-x";
        });
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(endpoint.method()), path);
        String body = VALID_BODIES.get(endpoint.key());
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return builder;
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String withoutTimestamp(MockHttpServletResponse response) throws Exception {
        ObjectNode body = (ObjectNode) json.readTree(response.getContentAsString());
        body.remove("timestamp");
        return json.writeValueAsString(body);
    }

    /** Counts connections the watched thread borrows; the relay and the pollers borrow on their own threads. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RequestThreadConnections {

        private final AtomicReference<Thread> watched = new AtomicReference<>();
        private final AtomicInteger borrowed = new AtomicInteger();

        void watch(Thread thread) {
            borrowed.set(0);
            watched.set(thread);
        }

        int borrowed() {
            return borrowed.get();
        }

        @Bean
        static BeanPostProcessor countingDataSource(ObjectProvider<RequestThreadConnections> counter) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource dataSource) || bean instanceof DelegatingDataSource) {
                        return bean;
                    }
                    return new DelegatingDataSource(dataSource) {
                        @Override
                        public Connection getConnection() throws SQLException {
                            counter.getObject().count();
                            return super.getConnection();
                        }
                    };
                }
            };
        }

        private void count() {
            if (watched.get() == Thread.currentThread()) {
                borrowed.incrementAndGet();
            }
        }
    }
}
