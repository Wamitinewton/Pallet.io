package io.pallet.buildqueue.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.dispatch.Dispatcher;
import io.pallet.buildqueue.observability.CapturedSpans.InMemorySpans;
import io.pallet.buildqueue.support.PushEvents;
import io.pallet.buildqueue.support.StubBuilder;
import io.pallet.buildqueue.support.StubBuilder.Mode;
import io.pallet.buildqueue.support.TopicProbe;
import io.pallet.buildqueue.support.TopicProbe.Received;
import io.pallet.common.events.EventHeaders;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * A push published inside a known trace is followed through the queue: the build stores the trace, its dispatch
 * continues it onto {@code build.dispatched}, the wait for a slot is a span of that trace with the stored timestamps,
 * and build-service's {@code build.started}, published in the trace it received, is consumed here in the same trace.
 */
@ObservabilityTestContext
class TracePropagationIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private Tracer tracer;

    @Autowired
    private Dispatcher dispatcher;

    @Autowired
    private BuildRepository builds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StubBuilder stub;

    @Autowired
    private InMemorySpans spans;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JsonMapper jsonMapper;

    private String orgId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM build_queue.builds");
        orgId = "org-" + UUID.randomUUID();
        stub.start(Mode.startOnly());
    }

    @AfterEach
    void tearDown() {
        stub.stop();
        jdbc.update("DELETE FROM build_queue.builds WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM build_queue.apps WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM build_queue.orgs WHERE org_id = ?", orgId);
    }

    @Test
    void oneTraceRunsFromThePushThroughTheDispatchToTheBuildersStart() {
        String traceId = hex(16);
        GitPushReceived push =
                PushEvents.push(orgId, "app-" + UUID.randomUUID()).build();
        UUID buildId = BuildIds.buildId(push.eventId());

        try (TopicProbe dispatched = new TopicProbe(kafka.getBootstrapServers(), Topics.BUILD_DISPATCHED)) {
            publishInTrace(push, traceId);
            Build queued = awaitStatus(buildId, BuildStatus.QUEUED);
            assertThat(traceIdOf(queued.traceparent())).isEqualTo(traceId);

            dispatcher.runRound();

            Received record = dispatched.awaitFirst(
                    orgId,
                    received -> buildId.toString()
                            .equals(jsonMapper
                                    .readTree(received.value())
                                    .path("buildId")
                                    .asString()));
            assertThat(traceIdOf(record.header(EventHeaders.TRACEPARENT))).isEqualTo(traceId);
        }
        Build running = awaitStatus(buildId, BuildStatus.RUNNING);

        SpanData started = await().atMost(WAIT)
                .until(() -> consumerSpan(traceId, Topics.BUILD_STARTED), Optional::isPresent)
                .orElseThrow();
        assertThat(started.getTraceId()).isEqualTo(traceId);

        List<SpanData> trace = spans.inTrace(traceId);
        String storedParent = spanIdOf(running.traceparent());
        SpanData dispatch = named(trace, BuildSpans.DISPATCH);
        assertThat(dispatch.getParentSpanId()).isEqualTo(storedParent);
        assertThat(dispatch.getAttributes().asMap().toString()).contains(buildId.toString());
        SpanData wait = named(trace, BuildSpans.WAIT);
        assertThat(wait.getParentSpanId()).isEqualTo(storedParent);
        assertThat(wait.getStartEpochNanos()).isEqualTo(epochNanos(running.enqueuedAt()));
        assertThat(wait.getEndEpochNanos()).isEqualTo(epochNanos(running.dispatchedAt()));
    }

    @Test
    void aBuildWithoutAStoredTraceIsDispatchedInATraceOfItsOwn() {
        GitPushReceived push =
                PushEvents.push(orgId, "app-" + UUID.randomUUID()).build();
        UUID buildId = BuildIds.buildId(push.eventId());
        publisher.publish(push);
        awaitStatus(buildId, BuildStatus.QUEUED);
        jdbc.update("UPDATE build_queue.builds SET traceparent = 'not-a-traceparent' WHERE build_id = ?", buildId);

        try (TopicProbe dispatched = new TopicProbe(kafka.getBootstrapServers(), Topics.BUILD_DISPATCHED)) {
            dispatcher.runRound();

            Received record = dispatched.awaitFirst(
                    orgId,
                    received -> buildId.toString()
                            .equals(jsonMapper
                                    .readTree(received.value())
                                    .path("buildId")
                                    .asString()));
            assertThat(record.header(EventHeaders.TRACEPARENT)).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
        }
        assertThat(awaitStatus(buildId, BuildStatus.RUNNING).dispatchedAt()).isNotNull();
    }

    private void publishInTrace(GitPushReceived push, String traceId) {
        Span webhook = tracer.spanBuilder()
                .setParent(tracer.traceContextBuilder()
                        .traceId(traceId)
                        .spanId(hex(8))
                        .sampled(true)
                        .build())
                .name("webhook")
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(webhook)) {
            publisher.publish(push);
        } finally {
            webhook.end();
        }
    }

    private Optional<SpanData> consumerSpan(String traceId, String topic) {
        return spans.inTrace(traceId).stream()
                .filter(span -> span.getKind() == SpanKind.CONSUMER)
                .filter(span -> span.getName().startsWith(topic))
                .findFirst();
    }

    private Build awaitStatus(UUID buildId, BuildStatus status) {
        return await().atMost(WAIT)
                .pollInterval(Duration.ofMillis(50))
                .until(
                        () -> builds.findByOrgIdAndBuildId(orgId, buildId),
                        found -> found.map(Build::status).equals(Optional.of(status)))
                .orElseThrow();
    }

    private static SpanData named(List<SpanData> trace, String name) {
        return trace.stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + name + " span in " + trace));
    }

    private static String traceIdOf(String traceparent) {
        return traceparent.split("-")[1];
    }

    private static String spanIdOf(String traceparent) {
        return traceparent.split("-")[2];
    }

    private static long epochNanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    private static String hex(int bytes) {
        byte[] random = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }
}
