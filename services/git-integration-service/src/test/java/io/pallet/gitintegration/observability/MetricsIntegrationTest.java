package io.pallet.gitintegration.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pallet.common.outbox.OutboxMetrics;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.access.ReverifyJobs;
import io.pallet.gitintegration.access.ReverifyMetrics;
import io.pallet.gitintegration.delivery.DeliveryMetrics;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.recovery.RecoveryJobs;
import io.pallet.gitintegration.repolink.RepoLinkApi;
import io.pallet.gitintegration.session.GitHubUserSessionStore;
import io.pallet.gitintegration.session.SessionMetrics;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class, Tokens.LocalDecoder.class})
class MetricsIntegrationTest {

    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);
    private static final String C = "c".repeat(40);
    private static final String D = "d".repeat(40);
    private static final String GITHUB_LOGIN = "fixture-dev";
    private static final long GITHUB_USER_ID = 9100001;
    private static final int MAX_CYCLES = 20;

    private static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(.*)})? .*$");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHA_SHAPE = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern LONG_NUMBER = Pattern.compile("[0-9]{5,}");
    private static final Set<String> NUMERIC_LABELS = Set.of("le", "quantile");

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private DeliveryMetrics deliveryMetrics;

    @Autowired
    private ReverifyMetrics reverifyMetrics;

    @Autowired
    private SessionMetrics sessionMetrics;

    @Autowired
    private OutboxMetrics outboxMetrics;

    @Autowired
    private GitHubUserSessionStore sessions;

    private ReadModelFixtures fixtures;
    private RepoLinkApi api;
    private final List<UUID> deliveries = new ArrayList<>();
    private String orgId;
    private String admin;
    private long installationId;
    private long repoId;
    private UUID appId;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new ReadModelFixtures(jdbc);
        api = new RepoLinkApi(mvc, json, github);
        orgId = fixtures.newOrg();
        installationId = fixtures.newInstallation();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        appId = fixtures.newApp(orgId, "ACTIVE");
        admin = fixtures.newMember(orgId, "admin", "ACTIVE");
        repoId = ThreadLocalRandom.current().nextLong(100_000, Long.MAX_VALUE);
        github.stubRepository(repoId, installationId, "push", false);
        github.stubTokenMint(installationId);
        github.stubBranch(repoId, "main", A);
        api.as(admin).signIn();
    }

    @AfterEach
    void tearDown() {
        deliveries.forEach(
                id -> jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id));
        jdbc.update("DELETE FROM git_integration.branch_heads WHERE app_id = ?", appId);
        jdbc.update("DELETE FROM git_integration.authorization_states WHERE user_id = ?", admin);
        sessions.delete(admin);
        fixtures.cleanUp();
    }

    @Test
    void aFullScenarioRegistersEveryCatalogMeterAndNoLabelCarriesAnIdentifier() throws Exception {
        double outboxLatencyBefore = timerCount(MetricsCatalog.PUSH_TO_OUTBOX_LATENCY);
        double manualBefore = count(MetricsCatalog.PUSHES_PUBLISHED, MetricsCatalog.TAG_TRIGGER, "MANUAL");
        double deniedBefore = count(MetricsCatalog.LINK_ACCESS_DENIED, MetricsCatalog.TAG_REASON, "not_accessible");
        double mintsBefore = count(MetricsCatalog.GITHUB_TOKEN_MINTS);

        assertStatus(api.as(admin).link(orgId, appId, installationId, repoId), 201);
        UUID unreachable = fixtures.newApp(orgId, "ACTIVE");
        assertStatus(api.link(orgId, unreachable, installationId, repoId + 1), 403);

        processed(push(A, B));
        processed(push(A, B));

        github.stubBranch(repoId, "main", C);
        assertStatus(api.build(orgId, appId, "metrics-" + UUID.randomUUID(), null), 202);

        github.stubBranch(repoId, "main", D);
        github.stubCompare(repoId, B, D, "ahead");
        assertThat(RecoveryJobs.reconcile(context)).isPositive();

        jdbc.update(
                "UPDATE git_integration.repo_links SET access_checked_at = now() - interval '2 days' WHERE app_id = ?",
                appId);
        github.stubCollaboratorPermission(repoId, GITHUB_LOGIN, GITHUB_USER_ID, "write", "write");
        assertThat(ReverifyJobs.run(context)).isPositive();

        assertStatus(api.as("user-" + UUID.randomUUID()).get(orgId, appId), 404);

        deliveryMetrics.refresh();
        reverifyMetrics.refresh();
        sessionMetrics.refresh();
        outboxMetrics.refresh();

        for (String name : catalogMeterNames()) {
            assertThat(registry.find(name).meter()).as(name).isNotNull();
        }
        assertThat(registry.find(MetricsCatalog.BREAKER_STATE)
                        .tag("name", "github-api")
                        .meters())
                .isNotEmpty();
        assertThat(registry.find(MetricsCatalog.BREAKER_STATE)
                        .tag("name", "github-user")
                        .meters())
                .isNotEmpty();
        assertThat(timerCount(MetricsCatalog.PUSH_TO_OUTBOX_LATENCY)).isEqualTo(outboxLatencyBefore + 1);
        assertThat(count(MetricsCatalog.PUSHES_PUBLISHED, MetricsCatalog.TAG_TRIGGER, "MANUAL"))
                .isEqualTo(manualBefore + 1);
        assertThat(count(MetricsCatalog.PUSHES_SKIPPED, MetricsCatalog.TAG_REASON, "DUPLICATE"))
                .isPositive();
        assertThat(count(MetricsCatalog.LINK_ACCESS_DENIED, MetricsCatalog.TAG_REASON, "not_accessible"))
                .isEqualTo(deniedBefore + 1);
        assertThat(count(MetricsCatalog.GITHUB_TOKEN_MINTS)).isGreaterThan(mintsBefore);
        assertThat(registry.get(MetricsCatalog.SESSIONS_ACTIVE).gauge().value()).isGreaterThanOrEqualTo(1);

        String scrape = scrape();
        assertThat(scrape).contains("git_webhook_ack_latency_seconds_bucket{").contains("le=\"0.2\"");
        assertLabelRule(scrape, Set.of(orgId, appId.toString(), admin, GITHUB_LOGIN, String.valueOf(repoId)));
    }

    @Test
    void noCatalogMeterDeclaresAForbiddenTagKey() {
        registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith(MetricsCatalog.PREFIX + "."))
                .forEach(meter -> assertThat(meter.getId().getTags())
                        .as(meter.getId().getName())
                        .noneMatch(tag -> MetricsCatalog.FORBIDDEN_TAG_KEYS.contains(tag.getKey())));
    }

    private void assertLabelRule(String scrape, Set<String> identifiers) {
        int checked = 0;
        for (String line : scrape.split("\n")) {
            Matcher sample = SAMPLE.matcher(line);
            if (line.startsWith("#") || !sample.matches() || !sample.group(1).startsWith(MetricsCatalog.PREFIX + "_")) {
                continue;
            }
            checked++;
            if (sample.group(2) == null) {
                continue;
            }
            Matcher label = LABEL.matcher(sample.group(2));
            while (label.find()) {
                String key = label.group(1);
                String value = label.group(2);
                if (NUMERIC_LABELS.contains(key)) {
                    continue;
                }
                assertThat(MetricsCatalog.FORBIDDEN_TAG_KEYS).as(line).doesNotContain(key);
                assertThat(value)
                        .as(line)
                        .doesNotContainPattern(UUID_SHAPE)
                        .doesNotContainPattern(SHA_SHAPE)
                        .doesNotContainPattern(LONG_NUMBER);
                assertThat(identifiers).as(line).doesNotContain(value);
            }
        }
        assertThat(checked).isGreaterThan(50);
    }

    private static List<String> catalogMeterNames() throws IllegalAccessException {
        List<String> names = new ArrayList<>();
        for (Field field : MetricsCatalog.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class
                    && !field.getName().startsWith("TAG_")
                    && !field.getName().startsWith("LISTENER_")
                    && ((String) field.get(null)).startsWith(MetricsCatalog.PREFIX + ".")) {
                names.add((String) field.get(null));
            }
        }
        assertThat(new HashSet<>(names)).as("every catalog name is unique").hasSameSizeAs(names);
        return names;
    }

    private WebhookFixtures.Delivery push(String before, String after) {
        return WebhookFixtures.delivery("push-main.json")
                .with("/installation/id", installationId)
                .with("/repository/id", repoId)
                .with("/before", before)
                .with("/after", after)
                .with("/head_commit/id", after);
    }

    private void processed(WebhookFixtures.Delivery delivery) throws Exception {
        UUID id = UUID.fromString(delivery.deliveryId());
        deliveries.add(id);
        assertStatus(delivery.post(mvc), 202);
        for (int cycle = 0; cycle < MAX_CYCLES && "RECEIVED".equals(status(id)); cycle++) {
            processor.processBatch();
        }
        assertThat(status(id)).isIn("PROCESSED", "IGNORED");
    }

    private String status(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM git_integration.webhook_deliveries WHERE delivery_id = ?", String.class, id);
    }

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double timerCount(String name) {
        Timer timer = registry.find(name).timer();
        return timer == null ? 0 : timer.count();
    }

    private static void assertStatus(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    }
}
