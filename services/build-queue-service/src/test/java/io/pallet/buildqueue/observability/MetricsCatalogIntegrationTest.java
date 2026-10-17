package io.pallet.buildqueue.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.buildqueue.observability.EveryStatusWalk.Walked;
import io.pallet.buildqueue.observability.QueueMetrics.Outcome;
import io.pallet.buildqueue.support.Counters;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.outbox.OutboxMetrics;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives builds through every status, then reads {@code /actuator/prometheus} as Prometheus would: every meter
 * ARCHITECTURE.md §Observability and operations documents is there, the walk moved the counts it should, and no label
 * carries a tenant, an app or a build. Counts are summed over every test context, since any of them may apply a
 * Kafka record.
 */
@ObservabilityTestContext
class MetricsCatalogIntegrationTest {

    private static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(.*)})? .*$");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHA_SHAPE = Pattern.compile("[0-9a-f]{40}");
    private static final Set<String> NUMERIC_LABELS = Set.of("le", "quantile");
    private static final String PROMETHEUS_PREFIX = QueueMetrics.PREFIX + "_";

    /** The families a dashboard or an alert reads, as Prometheus names them. */
    private static final List<String> SCRAPED_FAMILIES = List.of(
            "build_queue_requests_total",
            "build_queue_requests_dropped_total",
            "build_queue_depth",
            "build_queue_in_flight",
            "build_queue_capacity",
            "build_queue_oldest_queued_age_seconds",
            "build_queue_oldest_dispatchable_age_seconds",
            "build_queue_wait_seconds_bucket",
            "build_queue_start_latency_seconds_bucket",
            "build_queue_dispatched_total",
            "build_queue_dispatch_round_seconds_count",
            "build_queue_dispatch_paused",
            "build_queue_finished_total",
            "build_queue_events_anomaly_total",
            "build_queue_lease_sweep_skipped_total",
            "build_queue_outbox_oldest_pending_age_seconds",
            "kafka_consumer_fetch_manager_records_lag_max");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private QueueGauges gauges;

    @Autowired
    private ApplicationContext context;

    private EveryStatusWalk walk;

    @BeforeEach
    void setUp() {
        walk = new EveryStatusWalk(context);
    }

    @AfterEach
    void tearDown() {
        walk.cleanUp();
    }

    @Test
    void everyStatusMovesItsMetersAndEveryDocumentedMeterIsScrapedWithBoundedLabels() throws Exception {
        Map<String, Double> before = finishedCounts();
        double queuedBefore = requests(QueueMetrics.RequestOutcome.QUEUED);
        double rejectedBefore = requests(QueueMetrics.RequestOutcome.REJECTED);
        double firstDispatchesBefore = Counters.count(QueueMetrics.DISPATCHED, QueueMetrics.TAG_ATTEMPT, "1");
        long waitsBefore = Counters.timerCount(QueueMetrics.WAIT);
        long startsBefore = Counters.timerCount(QueueMetrics.START_LATENCY);
        double lateResultsBefore = anomalies(QueueMetrics.Anomaly.LATE_RESULT);

        Walked walked = walk.walk(UnaryOperator.identity());
        gauges.refresh();

        Map<String, Double> after = finishedCounts();
        assertThat(moved(before, after, Outcome.REJECTED, BuildCancelled.REASON_QUEUE_FULL))
                .isPositive();
        assertThat(moved(before, after, Outcome.CANCELLED, BuildCancelled.REASON_ORG_DELETED))
                .isPositive();
        assertThat(moved(before, after, Outcome.CANCELLED, BuildCancelled.REASON_USER_CANCELLED))
                .isPositive();
        assertThat(moved(before, after, Outcome.SUPERSEDED, BuildCancelled.REASON_SUPERSEDED))
                .isPositive();
        assertThat(moved(before, after, Outcome.SUCCEEDED, QueueMetrics.REASON_NONE))
                .isPositive();
        assertThat(moved(before, after, Outcome.FAILED, QueueMetrics.REASON_NONE))
                .isPositive();
        assertThat(moved(before, after, Outcome.EXPIRED, BuildCancelled.REASON_LEASE_EXPIRED))
                .isPositive();
        assertThat(moved(before, after, Outcome.SUCCEEDED, QueueMetrics.REASON_LATE_RESULT))
                .isPositive();
        assertThat(requests(QueueMetrics.RequestOutcome.QUEUED)).isGreaterThan(queuedBefore);
        assertThat(requests(QueueMetrics.RequestOutcome.REJECTED)).isGreaterThan(rejectedBefore);
        assertThat(Counters.count(QueueMetrics.DISPATCHED, QueueMetrics.TAG_ATTEMPT, "1"))
                .isGreaterThanOrEqualTo(firstDispatchesBefore + 3);
        assertThat(Counters.timerCount(QueueMetrics.WAIT)).isGreaterThanOrEqualTo(waitsBefore + 3);
        assertThat(Counters.timerCount(QueueMetrics.START_LATENCY)).isGreaterThanOrEqualTo(startsBefore + 3);
        assertThat(anomalies(QueueMetrics.Anomaly.LATE_RESULT)).isGreaterThan(lateResultsBefore);

        for (String name : catalogMeterNames()) {
            assertThat(registry.find(name).meter()).as(name).isNotNull();
        }
        assertThat(registry.find(QueueMetrics.PREFIX + "." + OutboxMetrics.OLDEST_PENDING_AGE)
                        .meter())
                .isNotNull();

        String scrape = scrape();
        for (String family : SCRAPED_FAMILIES) {
            assertThat(scrape).as(family).containsPattern("(?m)^" + family + "[{ ]");
        }
        assertThat(scrape).contains("build_queue_wait_seconds_bucket{").contains("le=\"1.0\"");
        Set<String> identifiers = new HashSet<>();
        identifiers.addAll(walked.orgIds());
        identifiers.addAll(walked.appIds());
        walked.buildIds().forEach(id -> identifiers.add(id.toString()));
        assertLabelsAreBounded(scrape, identifiers);
    }

    @Test
    void noQueueMeterDeclaresATenantOrBuildTagKey() {
        registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith(QueueMetrics.PREFIX + "."))
                .forEach(meter -> assertThat(meter.getId().getTags())
                        .as(meter.getId().getName())
                        .noneMatch(tag -> QueueMetrics.FORBIDDEN_TAG_KEYS.contains(tag.getKey())));
    }

    @Test
    void everyTriggerIsAKnownRequestLabel() {
        assertThat(QueueMetrics.TRIGGERS)
                .containsExactlyInAnyOrder(
                        GitPushReceived.TRIGGER_WEBHOOK,
                        GitPushReceived.TRIGGER_MANUAL,
                        GitPushReceived.TRIGGER_LINKED,
                        GitPushReceived.TRIGGER_RECONCILED);
    }

    private void assertLabelsAreBounded(String scrape, Set<String> identifiers) {
        int checked = 0;
        for (String line : scrape.split("\n")) {
            Matcher sample = SAMPLE.matcher(line);
            if (line.startsWith("#") || !sample.matches() || !sample.group(1).startsWith(PROMETHEUS_PREFIX)) {
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
                assertThat(QueueMetrics.FORBIDDEN_TAG_KEYS).as(line).doesNotContain(key);
                assertThat(value).as(line).doesNotContainPattern(UUID_SHAPE).doesNotContainPattern(SHA_SHAPE);
                assertThat(identifiers).as(line).doesNotContain(value);
            }
        }
        assertThat(checked).isGreaterThan(50);
    }

    private static List<String> catalogMeterNames() throws IllegalAccessException {
        List<String> names = new ArrayList<>();
        for (Field field : QueueMetrics.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class
                    && ((String) field.get(null)).startsWith(QueueMetrics.PREFIX + ".")) {
                names.add((String) field.get(null));
            }
        }
        assertThat(new HashSet<>(names)).as("every catalog name is unique").hasSameSizeAs(names);
        return names;
    }

    private static Map<String, Double> finishedCounts() {
        Map<String, Double> counts = new HashMap<>();
        for (Outcome outcome : Outcome.values()) {
            for (String reason : outcome.reasons()) {
                counts.put(
                        key(outcome, reason),
                        Counters.count(
                                QueueMetrics.FINISHED,
                                QueueMetrics.TAG_OUTCOME,
                                QueueMetrics.tag(outcome),
                                QueueMetrics.TAG_REASON,
                                reason));
            }
        }
        return counts;
    }

    private static double moved(Map<String, Double> before, Map<String, Double> after, Outcome outcome, String reason) {
        return after.get(key(outcome, reason)) - before.get(key(outcome, reason));
    }

    private static String key(Outcome outcome, String reason) {
        return outcome + "/" + reason;
    }

    private static double requests(QueueMetrics.RequestOutcome outcome) {
        return Counters.count(
                QueueMetrics.REQUESTS,
                QueueMetrics.TAG_OUTCOME,
                QueueMetrics.tag(outcome),
                QueueMetrics.TAG_TRIGGER,
                GitPushReceived.TRIGGER_WEBHOOK);
    }

    private static double anomalies(QueueMetrics.Anomaly kind) {
        return Counters.count(QueueMetrics.EVENTS_ANOMALY, QueueMetrics.TAG_KIND, QueueMetrics.tag(kind));
    }

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
