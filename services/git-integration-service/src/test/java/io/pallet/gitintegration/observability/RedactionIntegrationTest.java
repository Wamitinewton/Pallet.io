package io.pallet.gitintegration.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.repolink.RepoLinkApi;
import io.pallet.gitintegration.session.GitHubUserSessionStore;
import io.pallet.gitintegration.support.CapturedSpans;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.TestSecrets;
import io.pallet.gitintegration.support.Tokens;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({
    RedisTestContainerConfiguration.class,
    GitHubApiStub.Properties.class,
    Tokens.LocalDecoder.class,
    CapturedSpans.Configuration.class
})
class RedactionIntegrationTest {

    private static final String API = "/api/v1/git-integration";
    private static final String CODE = "fedcba98765432100123";
    private static final String BEFORE = "a".repeat(40);
    private static final String AFTER = "b".repeat(40);
    private static final String UNSEEN = "c".repeat(40);
    private static final String AFTER_GAP = "d".repeat(40);
    private static final String COMMIT_MESSAGE = "Tune startup probe";
    private static final String COMMITTER_EMAIL = "fixture-dev@users.noreply.example";
    private static final List<String> TOKEN_PREFIXES = List.of("ghs_", "ghu_", "ghr_", "Bearer ey", "Bearer gh");

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private CapturedSpans spans;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private GitHubUserSessionStore sessions;

    private final CapturingAppender logs = new CapturingAppender();
    private final List<UUID> deliveries = new ArrayList<>();
    private Logger root;
    private Logger service;
    private Level serviceLevel;
    private ReadModelFixtures fixtures;
    private RepoLinkApi api;
    private String orgId;
    private String admin;
    private long installationId;
    private long repoId;
    private UUID appId;

    @BeforeEach
    void setUp() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        service = (Logger) LoggerFactory.getLogger("io.pallet");
        serviceLevel = service.getLevel();
        service.setLevel(Level.DEBUG);
        logs.start();
        root.addAppender(logs);
        spans.clear();

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
        github.stubBranch(repoId, "main", BEFORE);
        github.stubCodeExchange();
        github.stubCurrentUser();
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        logs.stop();
        service.setLevel(serviceLevel);
        deliveries.forEach(
                id -> jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id));
        jdbc.update("DELETE FROM git_integration.branch_heads WHERE app_id = ?", appId);
        jdbc.update("DELETE FROM git_integration.authorization_states WHERE user_id = ?", admin);
        sessions.delete(admin);
        fixtures.cleanUp();
    }

    @Test
    void noCredentialCodeStateOrCommitMessageReachesALogLineASpanOrAMeter() throws Exception {
        String state = authorize();
        MockHttpServletResponse echoed = api.perform(
                get(API + "/github/session").queryParam("code", CODE).queryParam("state", state));
        assertThat(echoed.getStatus()).isEqualTo(200);

        assertThat(api.link(orgId, appId, installationId, repoId).getStatus()).isEqualTo(201);

        WebhookFixtures.Delivery push = WebhookFixtures.delivery("push-main.json")
                .with("/installation/id", installationId)
                .with("/repository/id", repoId)
                .with("/before", BEFORE)
                .with("/after", AFTER)
                .with("/head_commit/id", AFTER);
        deliveries.add(UUID.fromString(push.deliveryId()));
        assertThat(push.post(mvc).getStatus()).isEqualTo(202);
        processor.processBatch();

        WebhookFixtures.Delivery gap = WebhookFixtures.delivery("push-main.json")
                .with("/installation/id", installationId)
                .with("/repository/id", repoId)
                .with("/before", UNSEEN)
                .with("/after", AFTER_GAP)
                .with("/head_commit/id", AFTER_GAP);
        github.stubCompare(repoId, AFTER, AFTER_GAP, "ahead");
        deliveries.add(UUID.fromString(gap.deliveryId()));
        assertThat(gap.post(mvc).getStatus()).isEqualTo(202);
        processor.processBatch();
        assertThat(github.requests(GitHubApiStub.comparePath(repoId, AFTER, AFTER_GAP)))
                .isNotEmpty();

        github.stubUnauthorized(GitHubApiStub.branchPath(repoId, "main"));
        MockHttpServletResponse refused = api.build(orgId, appId, "redaction-" + UUID.randomUUID(), null);
        assertThat(refused.getStatus()).isGreaterThanOrEqualTo(400);

        List<String> secrets = List.of(
                CODE,
                state,
                TestSecrets.WEBHOOK_SECRET,
                TestSecrets.PREVIOUS_WEBHOOK_SECRET,
                TestSecrets.CLIENT_SECRET,
                TestSecrets.STATE_SIGNING_KEY,
                TestSecrets.USER_SESSION_KEY,
                "PRIVATE KEY",
                COMMIT_MESSAGE);
        for (String surface : everything()) {
            for (String secret : secrets) {
                assertThat(surface).doesNotContain(secret);
            }
            for (String prefix : TOKEN_PREFIXES) {
                assertThat(surface).doesNotContain(prefix);
            }
        }
        assertThat(refused.getContentAsString()).doesNotContain("Bad credentials");
        List<String> outboundUrls = spans.all().stream()
                .filter(span -> span.getKind() == SpanKind.CLIENT)
                .map(span -> span.getAttributes().get(AttributeKey.stringKey("http.url")))
                .filter(url -> url != null && url.startsWith(GitHubApiStub.baseUrl()))
                .toList();
        assertThat(outboundUrls).isNotEmpty().noneMatch(url -> url.contains("?"));
        assertThat(aboveDebug()).noneMatch(line -> line.contains(COMMITTER_EMAIL));
        assertThat(accessLines())
                .anyMatch(line -> line.contains("GET " + API + "/github/session?code=***&state=***")
                        && line.contains("bearer 200"));
    }

    private String authorize() throws Exception {
        api.as(admin);
        String url = api.data(api.perform(post(API + "/github/authorizations")))
                .path("authorizeUrl")
                .asString();
        String state =
                UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
        MockHttpServletResponse completed = api.perform(post(API + "/github/authorizations/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", CODE, "state", state))));
        assertThat(completed.getStatus()).as(completed.getContentAsString()).isEqualTo(200);
        return state;
    }

    private List<String> everything() {
        List<String> surfaces = new ArrayList<>(logged(Level.TRACE));
        surfaces.addAll(spans.everyNameAndAttribute());
        registry.getMeters()
                .forEach(meter -> meter.getId()
                        .getTags()
                        .forEach(tag ->
                                surfaces.add(meter.getId().getName() + " " + tag.getKey() + "=" + tag.getValue())));
        return surfaces;
    }

    private List<String> aboveDebug() {
        return logged(Level.INFO);
    }

    private List<String> accessLines() {
        return logs.list.stream()
                .filter(event -> event.getLoggerName().equals("pallet.access"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private List<String> logged(Level atLeast) {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : logs.list) {
            if (!event.getLevel().isGreaterOrEqual(atLeast)) {
                continue;
            }
            lines.add(event.getFormattedMessage());
            event.getMDCPropertyMap().forEach((key, value) -> lines.add(key + "=" + value));
            IThrowableProxy throwable = event.getThrowableProxy();
            if (throwable != null) {
                lines.add(ThrowableProxyUtil.asString(throwable));
            }
        }
        return lines;
    }

    /** The relay and the scheduler log from their own threads while a test reads. */
    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> list = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            list.add(event);
        }
    }
}
