package io.pallet.gitintegration.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.pallet.common.events.BuildStarted;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.checks.CheckRunJobs;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.support.CapturedSpans;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
@AutoConfigureMockMvc
@Import({
    RedisTestContainerConfiguration.class,
    GitHubApiStub.Properties.class,
    CapturedSpans.Configuration.class,
    TracePropagationIntegrationTest.BuildQueueProbe.class
})
class TracePropagationIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String BEFORE = "a".repeat(40);
    private static final String AFTER = "b".repeat(40);
    private static final String OUTBOX_RELAY = "outbox relay";
    private static final long CHECK_RUN_ID = 7_654_321;

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private CapturedSpans spans;

    @Autowired
    private Tracer tracer;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private ApplicationContext context;

    private ReadModelFixtures fixtures;
    private UUID deliveryId;
    private UUID appId;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        spans.clear();
    }

    @AfterEach
    void tearDown() {
        if (deliveryId != null) {
            jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", deliveryId);
        }
        if (appId != null) {
            jdbc.update("DELETE FROM git_integration.branch_heads WHERE app_id = ?", appId);
        }
        fixtures.cleanUp();
    }

    @Test
    void aPushIsOneTraceFromGitHubsPostThroughTheOutboxToTheBuildQueueConsumer() {
        String orgId = fixtures.newOrg();
        long installationId = fixtures.newInstallation();
        long repoId = ThreadLocalRandom.current().nextLong(100_000, Long.MAX_VALUE);
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        appId = fixtures.newRepoLink(orgId, installationId, repoId);
        jdbc.update(
                "INSERT INTO git_integration.branch_heads (app_id, branch, head_sha) VALUES (?, 'main', ?)",
                appId,
                BEFORE);
        WebhookFixtures.Delivery push = WebhookFixtures.delivery("push-main.json")
                .with("/installation/id", installationId)
                .with("/repository/id", repoId)
                .with("/before", BEFORE)
                .with("/after", AFTER)
                .with("/head_commit/id", AFTER);
        deliveryId = UUID.fromString(push.deliveryId());

        assertThat(push.post(mvc).getStatus()).isEqualTo(202);
        SpanData request = spans.all().stream()
                .filter(span -> span.getKind() == SpanKind.SERVER)
                .findFirst()
                .orElseThrow();
        processor.processBatch();

        String traceId = request.getTraceId();
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(spans.inTrace(traceId))
                        .extracting(SpanData::getKind)
                        .contains(SpanKind.SERVER, SpanKind.PRODUCER, SpanKind.CONSUMER));

        SpanData processing = named(traceId, DeliveryProcessor.SPAN_NAME);
        SpanData relay = named(traceId, OUTBOX_RELAY);
        assertThat(ancestorsOf(processing, traceId)).contains(request.getSpanId());
        assertThat(ancestorsOf(relay, traceId))
                .as("the relay continues the trace captured at append time, not its own thread's")
                .contains(processing.getSpanId());
        for (SpanData span : new SpanData[] {request, processing}) {
            assertThat(span.getAttributes().get(AttributeKey.stringKey(SpanAttributes.EVENT)))
                    .isEqualTo("push");
            assertThat(span.getAttributes().get(AttributeKey.stringKey(SpanAttributes.DELIVERY_ID)))
                    .isEqualTo(deliveryId.toString());
            assertThat(span.getAttributes().get(AttributeKey.stringKey(SpanAttributes.INSTALLATION_ID)))
                    .isEqualTo(String.valueOf(installationId));
        }
    }

    @Test
    void aCheckRunWriteContinuesTheTraceOfTheBuildEventThatAskedForIt() {
        String orgId = fixtures.newOrg();
        long installationId = fixtures.newInstallation();
        long repoId = ThreadLocalRandom.current().nextLong(100_000, Long.MAX_VALUE);
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        appId = fixtures.newRepoLink(orgId, installationId, repoId);
        github.stubTokenMint(installationId);
        github.stubCheckRunCreated(repoId, CHECK_RUN_ID, 0);
        jdbc.update("UPDATE git_integration.check_runs SET reported_revision = desired_revision");

        Span build = tracer.nextSpan().name("build-service").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(build)) {
            publisher.publish(BuildStarted.of(orgId, "bld-1", appId.toString(), AFTER, UUID.randomUUID()));
        } finally {
            build.end();
        }
        await().atMost(WAIT)
                .until(() -> jdbc.queryForObject(
                                "SELECT count(*) FROM git_integration.check_runs WHERE app_id = ?",
                                Integer.class,
                                appId)
                        == 1);
        CheckRunJobs.report(context);

        assertThat(spans.inTrace(build.context().traceId()))
                .extracting(SpanData::getName)
                .contains(CheckRunJobs.spanName());
    }

    private List<String> ancestorsOf(SpanData span, String traceId) {
        Map<String, SpanData> byId = spans.inTrace(traceId).stream()
                .collect(Collectors.toMap(SpanData::getSpanId, Function.identity(), (first, second) -> first));
        List<String> ancestors = new ArrayList<>();
        for (SpanData parent = byId.get(span.getParentSpanId());
                parent != null;
                parent = byId.get(parent.getParentSpanId())) {
            ancestors.add(parent.getSpanId());
        }
        return ancestors;
    }

    private SpanData named(String traceId, String name) {
        return spans.inTrace(traceId).stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + name + " span in trace " + traceId));
    }

    /** Stands in for build-queue-service, the consumer the architecture names at the end of the trace. */
    @TestConfiguration(proxyBeanMethods = false)
    static class BuildQueueProbe {

        @Bean
        Listener buildQueueProbeListener() {
            return new Listener();
        }

        static class Listener {

            @KafkaListener(
                    id = "build-queue-trace-probe",
                    idIsGroup = false,
                    groupId = "build-queue-trace-probe-${random.uuid}",
                    topics = GitPushReceived.TYPE)
            void onMessage(ConsumerRecord<String, JsonNode> record) {}
        }
    }
}
