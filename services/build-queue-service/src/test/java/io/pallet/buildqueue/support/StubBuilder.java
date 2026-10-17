package io.pallet.buildqueue.support;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.pallet.common.events.BuildFailed;
import io.pallet.common.events.BuildStarted;
import io.pallet.common.events.BuildSucceeded;
import io.pallet.common.events.EventHeaders;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.events.Topics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for build-service on the real broker. Honours its contract (ARCHITECTURE.md §Contract with build-service):
 * runs each {@code buildId} at most once whatever the attempt, refuses an attempt past its {@code startBy}, and reports
 * through {@code build.started} then {@code build.succeeded} or {@code build.failed}, keyed by {@code orgId}, each
 * published in a span continuing the dispatch's trace. A {@link Mode} can break it on purpose. {@code @TestComponent}
 * keeps it out of scanning; import it.
 *
 * <p>It reads {@code build.dispatched} from the end as of {@link #start}, on assigned partitions with no group, so
 * dispatches left on the topic by earlier tests are never run.
 */
@TestComponent
public class StubBuilder implements AutoCloseable {

    public static final String FAILURE_REASON = "stub builder failed the build";

    private static final Duration DEFAULT_RUN_TIME = Duration.ofMillis(200);
    private static final Duration POLL = Duration.ofMillis(100);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    /** How the stub answers a dispatch it accepts. */
    public enum Behaviour {
        /** Started, then succeeded. */
        NORMAL,
        /** Consumes and does nothing, holding nothing: a dead builder, or a dispatch lost on the way. */
        IGNORE,
        /** Started, then failed. */
        FAIL,
        /** Started, never finished: a builder that dies mid-build. */
        START_ONLY,
        /** Waits, then starts even past {@code startBy}: a contract violator. Succeeds afterwards. */
        LATE_START,
        /** Picks the dispatch up after a delay, then honours {@code startBy} as {@link #NORMAL} does: a backed-up builder. */
        SLOW_PICKUP
    }

    /**
     * {@code runTime} is how long a build runs; {@code startDelay} is how long {@link Behaviour#LATE_START} waits to
     * start and {@link Behaviour#SLOW_PICKUP} waits to pick the dispatch up.
     */
    public record Mode(Behaviour behaviour, Duration runTime, Duration startDelay) {

        public static Mode normal() {
            return new Mode(Behaviour.NORMAL, DEFAULT_RUN_TIME, Duration.ZERO);
        }

        public static Mode ignore() {
            return new Mode(Behaviour.IGNORE, Duration.ZERO, Duration.ZERO);
        }

        public static Mode fail() {
            return new Mode(Behaviour.FAIL, DEFAULT_RUN_TIME, Duration.ZERO);
        }

        public static Mode slow(Duration runTime) {
            return new Mode(Behaviour.NORMAL, runTime, Duration.ZERO);
        }

        public static Mode startOnly() {
            return new Mode(Behaviour.START_ONLY, Duration.ZERO, Duration.ZERO);
        }

        public static Mode lateStart(Duration startDelay) {
            return new Mode(Behaviour.LATE_START, DEFAULT_RUN_TIME, startDelay);
        }

        public static Mode slowPickup(Duration pickupDelay) {
            return new Mode(Behaviour.SLOW_PICKUP, DEFAULT_RUN_TIME, pickupDelay);
        }
    }

    /** The fields of {@code build.dispatched} the stub acts on, and the trace the record carried. */
    public record Dispatch(
            String buildId,
            String orgId,
            String appId,
            String commitSha,
            UUID pushEventId,
            Instant startBy,
            String traceparent) {}

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final String bootstrapServers;
    private final ObjectProvider<Tracer> tracer;

    private final Set<String> accepted = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> received = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> starts = new ConcurrentHashMap<>();
    private final AtomicInteger droppedLate = new AtomicInteger();
    private final AtomicInteger droppedDuplicate = new AtomicInteger();

    private volatile Mode mode = Mode.normal();
    private volatile boolean running;
    private KafkaConsumer<String, String> consumer;
    private Thread poller;
    private ExecutorService builds;

    StubBuilder(
            KafkaTemplate<String, Object> kafkaTemplate,
            JsonMapper jsonMapper,
            KafkaContainer kafka,
            ObjectProvider<Tracer> tracer) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.bootstrapServers = kafka.getBootstrapServers();
        this.tracer = tracer;
    }

    /** Starts reading dispatches published from now on; returns once the read position is fixed. */
    public synchronized void start(Mode mode) {
        if (running) {
            throw new IllegalStateException("StubBuilder is already running");
        }
        this.mode = mode;
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class));
        List<TopicPartition> partitions = consumer.partitionsFor(Topics.BUILD_DISPATCHED).stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);
        builds = Executors.newVirtualThreadPerTaskExecutor();
        running = true;
        poller = Thread.ofVirtual().name("stub-builder").start(this::poll);
    }

    /** Changes how dispatches consumed from now on are answered; builds already accepted keep their mode. */
    public void mode(Mode mode) {
        this.mode = mode;
    }

    /** Every build id the stub has accepted to run, in any mode. */
    public Set<String> accepted() {
        return Set.copyOf(accepted);
    }

    /** Dispatches consumed for {@code buildId}, every attempt and mode included. */
    public int received(String buildId) {
        return count(received, buildId);
    }

    /** {@code build.started} reports published for {@code buildId}: more than one means the build ran twice. */
    public int starts(String buildId) {
        return count(starts, buildId);
    }

    /** Attempts refused because their {@code startBy} had passed. */
    public int droppedLate() {
        return droppedLate.get();
    }

    /** Dispatches ignored because the build was already accepted, under any attempt. */
    public int droppedDuplicate() {
        return droppedDuplicate.get();
    }

    /** Stops consuming and abandons builds in progress: nothing more is reported. */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        consumer.wakeup();
        builds.shutdownNow();
        try {
            poller.join(STOP_TIMEOUT);
            builds.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        accepted.clear();
        received.clear();
        starts.clear();
        droppedLate.set(0);
        droppedDuplicate.set(0);
    }

    @Override
    public void close() {
        stop();
    }

    private void poll() {
        try (KafkaConsumer<String, String> owned = consumer) {
            while (running) {
                for (ConsumerRecord<String, String> record : owned.poll(POLL)) {
                    receive(dispatch(record));
                }
            }
        } catch (WakeupException e) {
            if (running) {
                throw e;
            }
        }
    }

    private void receive(Dispatch dispatch) {
        Mode current = mode;
        received.computeIfAbsent(dispatch.buildId(), id -> new AtomicInteger()).incrementAndGet();
        switch (current.behaviour()) {
            case IGNORE -> {}
            case SLOW_PICKUP ->
                builds.submit(() -> {
                    Thread.sleep(current.startDelay());
                    accept(dispatch, current);
                    return null;
                });
            default -> accept(dispatch, current);
        }
    }

    /** A stale attempt is refused whether or not the build is already held: it may never start. */
    private void accept(Dispatch dispatch, Mode mode) {
        if (mode.behaviour() != Behaviour.LATE_START && !Instant.now().isBefore(dispatch.startBy())) {
            droppedLate.incrementAndGet();
            return;
        }
        if (!accepted.add(dispatch.buildId())) {
            droppedDuplicate.incrementAndGet();
            return;
        }
        builds.submit(() -> {
            run(dispatch, mode);
            return null;
        });
    }

    private void run(Dispatch dispatch, Mode mode) throws InterruptedException {
        if (mode.behaviour() == Behaviour.LATE_START) {
            Thread.sleep(mode.startDelay());
        }
        send(
                dispatch,
                BuildStarted.of(
                        dispatch.orgId(),
                        dispatch.buildId(),
                        dispatch.appId(),
                        dispatch.commitSha(),
                        dispatch.pushEventId()));
        starts.computeIfAbsent(dispatch.buildId(), id -> new AtomicInteger()).incrementAndGet();
        if (mode.behaviour() == Behaviour.START_ONLY) {
            return;
        }
        Thread.sleep(mode.runTime());
        if (mode.behaviour() == Behaviour.FAIL) {
            send(
                    dispatch,
                    BuildFailed.of(
                            dispatch.orgId(),
                            dispatch.buildId(),
                            dispatch.appId(),
                            dispatch.commitSha(),
                            dispatch.pushEventId(),
                            FAILURE_REASON));
        } else {
            send(
                    dispatch,
                    BuildSucceeded.of(
                            dispatch.orgId(),
                            dispatch.buildId(),
                            dispatch.appId(),
                            dispatch.commitSha(),
                            dispatch.pushEventId()));
        }
    }

    /** Publishes in a child span of the dispatch's trace, as build-service's listener observation would. */
    private void send(Dispatch dispatch, PlatformEvent event) {
        Tracer available = tracer.getIfAvailable();
        String[] traceparent =
                dispatch.traceparent() == null ? null : dispatch.traceparent().split("-");
        if (available == null || traceparent == null || traceparent.length != 4) {
            send(event);
            return;
        }
        Span span = available
                .spanBuilder()
                .setParent(available
                        .traceContextBuilder()
                        .traceId(traceparent[1])
                        .spanId(traceparent[2])
                        .sampled("01".equals(traceparent[3]))
                        .build())
                .name("stub-builder " + event.eventType())
                .start();
        try (Tracer.SpanInScope ignored = available.withSpan(span)) {
            send(event);
        } finally {
            span.end();
        }
    }

    private void send(PlatformEvent event) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(event.eventType(), event.orgId(), event);
        record.headers()
                .add(EventHeaders.EVENT_ID, utf8(event.eventId().toString()))
                .add(EventHeaders.EVENT_TYPE, utf8(event.eventType()))
                .add(EventHeaders.ORG_ID, utf8(event.orgId()));
        try {
            kafkaTemplate.send(record).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("StubBuilder could not publish " + event.eventType(), e);
        }
    }

    private Dispatch dispatch(ConsumerRecord<String, String> record) {
        JsonNode node = jsonMapper.readTree(record.value());
        Header traceparent = record.headers().lastHeader(EventHeaders.TRACEPARENT);
        return new Dispatch(
                node.path("buildId").asString(),
                node.path("orgId").asString(),
                node.path("appId").asString(),
                node.path("commitSha").asString(),
                UUID.fromString(node.path("pushEventId").asString()),
                Instant.parse(node.path("startBy").asString()),
                traceparent == null ? null : new String(traceparent.value(), StandardCharsets.UTF_8));
    }

    private static int count(Map<String, AtomicInteger> counts, String buildId) {
        AtomicInteger count = counts.get(buildId);
        return count == null ? 0 : count.get();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
