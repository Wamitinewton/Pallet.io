package io.pallet.gitintegration.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.outbox.OutboxWriter;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.delivery.DeliveryTestConfiguration.CapturedSpans;
import io.pallet.gitintegration.delivery.DeliveryTestConfiguration.ScriptedHandler;
import io.pallet.gitintegration.delivery.NeedsGitHub.Lookup;
import io.pallet.gitintegration.scm.ScmProvider;
import io.pallet.gitintegration.scm.ScmProvider.ScmInstallation;
import io.pallet.gitintegration.support.GitHubApiStub;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Import({RedisTestContainerConfiguration.class, DeliveryTestConfiguration.class})
class DeliveryProcessorIntegrationTest {

    private static final long INSTALLATION_ID = 41000001L;
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";
    private static final AtomicReference<Runnable> DURING_LOOKUP = new AtomicReference<>(() -> {});

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        GitHubApiStub.register(registry);
    }

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private DeliveryMetrics deliveryMetrics;

    @Autowired
    private ScriptedHandler handler;

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private CapturedSpans spans;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private DeliveryRows rows;

    @BeforeEach
    void setUp() {
        rows = new DeliveryRows(jdbc);
        handler.reset();
        spans.clear();
        DURING_LOOKUP.set(() -> {});
    }

    @AfterEach
    void tearDown() {
        rows.removeAll();
    }

    @Test
    void aProcessedDeliveryCommitsItsOutboxRowAndItsStatusTogether() {
        handler.script(context -> {
            outbox.append(pushEvent(context));
            return DeliveryOutcome.PROCESSED;
        });
        UUID id = rows.insert("repository-archived.json");
        double processedBefore = counter(DeliveryMetrics.PROCESSED, "event", "repository");

        processUntilHandled(id);

        Map<String, Object> row = rows.row(id);
        assertThat(row).containsEntry("status", "PROCESSED").containsEntry("attempts", 0);
        assertThat(row.get("processed_at")).isNotNull();
        assertThat(outboxRows(id)).isOne();
        assertThat(handler.calls(id)).isOne();
        assertThat(counter(DeliveryMetrics.PROCESSED, "event", "repository")).isEqualTo(processedBefore + 1);
    }

    @Test
    void aFailingHandlerRollsItsOutboxRowBackAndSchedulesARetry() {
        handler.script(context -> {
            outbox.append(pushEvent(context));
            throw new IllegalStateException("handler failed");
        });
        UUID id = rows.insert("repository-archived.json");
        Instant before = databaseNow();

        processUntilHandled(id);

        Map<String, Object> row = rows.row(id);
        assertThat(row).containsEntry("status", "RECEIVED").containsEntry("attempts", 1);
        assertThat((String) row.get("last_error")).isEqualTo("IllegalStateException: handler failed");
        assertThat(((Timestamp) row.get("next_attempt_at")).toInstant()).isAfter(before);
        assertThat(rows.secondsUntilDue(id)).isPositive();
        assertThat(outboxRows(id)).isZero();
    }

    @Test
    void aDeliveryIsParkedAfterMaxAttemptsFailures() {
        handler.script(context -> {
            throw new IllegalStateException("still failing");
        });
        UUID id = rows.insert("repository-archived.json");
        double errorsBefore = counter(DeliveryMetrics.FAILURES, "kind", DeliveryMetrics.KIND_ERROR);

        for (int attempt = 1; attempt <= 3; attempt++) {
            rows.makeDue(id);
            processUntilHandled(id);
        }

        assertThat(rows.row(id)).containsEntry("status", "PARKED").containsEntry("attempts", 3);
        assertThat(handler.calls(id)).isEqualTo(3);
        assertThat(counter(DeliveryMetrics.FAILURES, "kind", DeliveryMetrics.KIND_ERROR))
                .isEqualTo(errorsBefore + 3);
        deliveryMetrics.refresh();
        assertThat(meters.get(DeliveryMetrics.PARKED).gauge().value()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aMalformedPayloadIsParkedOnTheFirstTryWithoutReachingTheHandler() {
        UUID id = rows.insert("repository-archived.json", Map.of("/repository/id", "not-a-number"), null);

        processUntilHandled(id);

        Map<String, Object> row = rows.row(id);
        assertThat(row).containsEntry("status", "PARKED").containsEntry("attempts", 1);
        assertThat((String) row.get("last_error"))
                .isEqualTo("MalformedPayloadException: repository.id: must be a positive integer");
        assertThat(handler.calls(id)).isZero();
    }

    @Test
    void aRateLimitedLookupWaitsForTheResetWithoutSpendingAnAttempt() {
        Instant resetAt = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        github.stubRateLimited(GitHubApiStub.installationPath(INSTALLATION_ID), resetAt);
        handler.script(context -> {
            throw new NeedsGitHub(new InstallationLookup(INSTALLATION_ID));
        });
        UUID id = rows.insert("repository-archived.json");
        double rateLimitedBefore = counter(DeliveryMetrics.FAILURES, "kind", DeliveryMetrics.KIND_RATE_LIMITED);

        processUntilHandled(id);

        Map<String, Object> row = rows.row(id);
        assertThat(row).containsEntry("status", "RECEIVED").containsEntry("attempts", 0);
        assertThat(((Timestamp) row.get("next_attempt_at")).toInstant()).isBetween(resetAt, resetAt.plusSeconds(5));
        assertThat((String) row.get("last_error")).startsWith("GitHubRateLimitedException");
        assertThat(counter(DeliveryMetrics.FAILURES, "kind", DeliveryMetrics.KIND_RATE_LIMITED))
                .isEqualTo(rateLimitedBefore + 1);
    }

    @Test
    void gitHubUnavailabilityBacksOffLongerEachTimeAndNeverParks() {
        handler.script(context -> {
            throw new ExternalServiceException("GitHub is unavailable.", "github-api circuit breaker is open", null);
        });
        UUID id = rows.insert("repository-archived.json");
        List<Double> delays = new ArrayList<>();

        for (int round = 0; round < 5; round++) {
            rows.makeDue(id);
            processUntilHandled(id);
            delays.add(rows.secondsUntilDue(id));
        }

        Map<String, Object> row = rows.row(id);
        assertThat(row)
                .containsEntry("status", "RECEIVED")
                .containsEntry("attempts", 0)
                .containsEntry("unavailable_streak", 5);
        assertThat(delays).isSorted().doesNotHaveDuplicates();
        assertThat(delays.getFirst()).isCloseTo(1.0, within(0.5));
        assertThat(delays.getLast()).isCloseTo(16.0, within(0.5));
    }

    @Test
    void aLookupIsAnsweredWithTheRowReleasedAndLeasedThenTheHandlerSucceeds() {
        github.stubInstallation(INSTALLATION_ID);
        InstallationLookup lookup = new InstallationLookup(INSTALLATION_ID);
        List<ScmInstallation> seen = new ArrayList<>();
        handler.script(context -> {
            if (context.lookups().answer(lookup).isEmpty()) {
                throw new NeedsGitHub(lookup);
            }
            seen.add(context.lookups().answer(lookup).orElseThrow());
            outbox.append(pushEvent(context));
            return DeliveryOutcome.PROCESSED;
        });
        UUID id = rows.insert("repository-archived.json");
        List<Double> leaseDuringCall = new ArrayList<>();
        List<Boolean> lockableDuringCall = new ArrayList<>();
        DURING_LOOKUP.set(() -> {
            leaseDuringCall.add(rows.secondsUntilDue(id));
            lockableDuringCall.add(lockable(id));
        });

        processUntilHandled(id);

        assertThat(rows.row(id)).containsEntry("status", "PROCESSED").containsEntry("attempts", 0);
        assertThat(handler.calls(id)).isEqualTo(2);
        assertThat(seen).singleElement().extracting(ScmInstallation::id).isEqualTo(INSTALLATION_ID);
        assertThat(leaseDuringCall)
                .singleElement()
                .satisfies(seconds -> assertThat(seconds).isBetween(25.0, 30.0));
        assertThat(lockableDuringCall).containsExactly(true);
        assertThat(outboxRows(id)).isOne();
    }

    @Test
    void lookupsThatNeverSettleCostAnAttemptAfterTheLastRound() {
        handler.script(context -> {
            throw new NeedsGitHub(new CountingLookup(context.lookups().size()));
        });
        UUID id = rows.insert("repository-archived.json");

        processUntilHandled(id);

        Map<String, Object> row = rows.row(id);
        assertThat(handler.calls(id)).isEqualTo(4);
        assertThat(row).containsEntry("status", "RECEIVED").containsEntry("attempts", 1);
        assertThat((String) row.get("last_error")).contains("did not settle within 3 rounds");
    }

    @Test
    void aSubscribedEventWithNoHandlerIsIgnored() {
        UUID id = rows.insert("installation-repositories-added.json");

        processUntilHandled(id);

        Map<String, Object> row = rows.row(id);
        assertThat(row)
                .containsEntry("status", "IGNORED")
                .containsEntry("outcome_reason", DeliveryProcessor.NO_HANDLER);
        assertThat(row.get("processed_at")).isNotNull();
    }

    @Test
    void theProcessingSpanContinuesTheWebhookRequestsTrace() {
        UUID id = rows.insert("repository-archived.json", Map.of(), "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01");

        processUntilHandled(id);

        assertThat(spans.all())
                .filteredOn(span -> span.getName().equals(DeliveryProcessor.SPAN_NAME))
                .filteredOn(span -> span.getTraceId().equals(TRACE_ID))
                .singleElement()
                .extracting(SpanData::getParentSpanId)
                .isEqualTo(PARENT_SPAN_ID);
    }

    @Test
    void theClaimQueryUsesThePendingPartialIndex() {
        List<String> plan = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return jdbc.queryForList(
                    "EXPLAIN "
                            + DeliveryRepository.CLAIM_NEXT.replace(
                                    ":excluded", "CAST('" + new UUID(0, 0) + "' AS uuid)"),
                    String.class);
        });

        assertThat(String.join("\n", plan)).contains("ix_webhook_deliveries_pending");
    }

    /** Runs cycles until this delivery has been through one (other tests' rows may be due ahead of it). */
    private void processUntilHandled(UUID id) {
        Map<String, Object> before = rows.row(id);
        for (int cycle = 0; cycle < 20 && rows.row(id).equals(before); cycle++) {
            processor.processBatch();
        }
    }

    private boolean lockable(UUID id) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
                .execute(status -> !jdbc.queryForList(
                                "SELECT delivery_id FROM git_integration.webhook_deliveries WHERE delivery_id = ?"
                                        + " FOR UPDATE SKIP LOCKED",
                                id)
                        .isEmpty()));
    }

    private int outboxRows(UUID deliveryId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.outbox_events WHERE event_id = ?",
                Integer.class,
                eventId(deliveryId));
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    private double counter(String name, String tag, String value) {
        var counter = meters.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    static UUID eventId(UUID deliveryId) {
        return UUID.nameUUIDFromBytes(("push:" + deliveryId).getBytes(StandardCharsets.UTF_8));
    }

    static GitPushReceived pushEvent(DeliveryContext context) {
        return GitPushReceived.of(
                eventId(context.deliveryId()),
                "org-delivery-test",
                Instant.now(),
                new GitPushReceived.Push(
                        "pallet-fixtures/hello-web",
                        "main",
                        "8d3e5b7a9c1f2e4d6b8a0c2e4f6a8b0d2c4e6f8a",
                        UUID.randomUUID().toString(),
                        GitPushReceived.PROVIDER_GITHUB,
                        INSTALLATION_ID,
                        700000001L,
                        null,
                        "1f9a6c0e3b0d2a4c8e7f6a5b4c3d2e1f0a9b8c7d",
                        false,
                        GitPushReceived.TRIGGER_WEBHOOK,
                        null,
                        null,
                        context.deliveryId().toString()));
    }

    record InstallationLookup(long installationId) implements Lookup<ScmInstallation> {

        @Override
        public ScmInstallation perform(ScmProvider scm) {
            DURING_LOOKUP.get().run();
            return scm.installation(installationId);
        }
    }

    record CountingLookup(int round) implements Lookup<String> {

        @Override
        public String perform(ScmProvider scm) {
            return "answer " + round;
        }
    }
}
