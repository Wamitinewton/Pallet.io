package io.pallet.common.outbox.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.outbox.OutboxMetrics;
import io.pallet.common.outbox.OutboxProperties;
import io.pallet.common.outbox.OutboxWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared harness for the outbox chaos tests: fault injectors, the ground-truth ledger, and the
 * guarantee that every injected fault is undone and every row a test created is removed, so one
 * test's failure cannot poison the next.
 */
@OutboxChaosTest
abstract class OutboxChaosSupport {

    protected static final Duration WAIT = Duration.ofSeconds(60);
    protected static final String MEMBER_ADDED_TOPIC = Topics.ORG_MEMBER_ADDED;

    @Autowired
    @Qualifier("kafkaProducer") protected HealthIndicator kafkaProducerHealth;

    @Autowired
    @Qualifier("outbox") protected HealthIndicator outboxHealth;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected TransactionTemplate transaction;

    @Autowired
    protected OutboxWriter writer;

    @Autowired
    protected OutboxMetrics outboxMetrics;

    @Autowired
    protected MeterRegistry registry;

    @Autowired
    protected KafkaContainer kafka;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected OutboxProperties properties;

    @Autowired
    protected TransactionalInbox inbox;

    @Autowired
    protected ApplicationContext context;

    protected BrokerFaults broker;
    protected PostgresFaults postgres;
    protected DeliveryLedger ledger;
    protected IdempotentConsumerModel consumer;

    private final List<Fault> faults = new CopyOnWriteArrayList<>();
    private final List<String> orgs = new CopyOnWriteArrayList<>();

    @BeforeEach
    void initHarness() {
        broker = new BrokerFaults(kafka);
        postgres = new PostgresFaults(jdbc, dataSource, properties.advisoryLockKey());
        ledger = new DeliveryLedger();
        consumer = new IdempotentConsumerModel(inbox, transaction);
    }

    @AfterEach
    void healAndCleanUp() {
        List<Fault> reversed = new ArrayList<>(faults);
        Collections.reverse(reversed);
        reversed.forEach(Fault::close);
        faults.clear();
        orgs.forEach(orgId -> {
            jdbc.update("DELETE FROM outbox_test.outbox_events WHERE org_id = ?", orgId);
        });
        orgs.clear();
        consumer.cleanUp(jdbc);
    }

    /** Registers a fault so it is undone even if the test fails before it closes it. */
    protected <F extends Fault> F track(F fault) {
        faults.add(fault);
        return fault;
    }

    protected String newOrg() {
        return newOrg("chaos");
    }

    /** {@code prefix} lets a fault's LIKE pattern cover exactly the orgs one test created. */
    protected String newOrg(String prefix) {
        String orgId = prefix + "-" + UUID.randomUUID();
        orgs.add(orgId);
        return orgId;
    }

    protected List<String> newOrgs(int count) {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            created.add(newOrg());
        }
        return created;
    }

    protected static OrgMemberAdded memberAdded(String orgId) {
        return OrgMemberAdded.of(orgId, "user-" + UUID.randomUUID(), "member@example.com");
    }

    /** Commits the events in one transaction and records them in the ledger once the commit returns. */
    protected void commit(PlatformEvent... events) {
        transaction.executeWithoutResult(status -> {
            for (PlatformEvent event : events) {
                writer.append(event);
            }
        });
        for (PlatformEvent event : events) {
            ledger.committed(event);
        }
    }

    protected void awaitDrained(Collection<String> orgIds) {
        await().atMost(WAIT)
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(openRows(orgIds))
                        .as("open outbox rows for %s", orgIds)
                        .isZero());
    }

    protected int openRows(Collection<String> orgIds) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_test.outbox_events WHERE status <> 'PUBLISHED' AND org_id IN ("
                        + placeholders(orgIds.size()) + ")",
                Integer.class,
                orgIds.toArray());
    }

    protected void awaitPublished(UUID eventId) {
        await().atMost(WAIT)
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(statusOf(eventId)).isEqualTo("PUBLISHED"));
    }

    protected String statusOf(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM outbox_test.outbox_events WHERE event_id = ?", String.class, eventId);
    }

    protected int attemptsOf(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT attempts FROM outbox_test.outbox_events WHERE event_id = ?", Integer.class, eventId);
    }

    protected Deliveries deliveries() {
        return Deliveries.collect(kafka, jsonMapper, ledger, MEMBER_ADDED_TOPIC);
    }

    protected double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    protected double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    protected double brokerFailures() {
        return counter(outboxMetric(OutboxMetrics.PUBLISH_FAILURES), OutboxMetrics.TAG_KIND, OutboxMetrics.KIND_BROKER);
    }

    protected double rowFailures() {
        return counter(outboxMetric(OutboxMetrics.PUBLISH_FAILURES), OutboxMetrics.TAG_KIND, OutboxMetrics.KIND_ROW);
    }

    protected String outboxMetric(String name) {
        return properties.metricsPrefix() + "." + name;
    }

    /** The module's readiness contributors: a paused broker or a backlog must not take a pod out of rotation. */
    protected void readinessIsUp() {
        assertThat(kafkaProducerHealth.health().getStatus()).isEqualTo(Status.UP);
        assertThat(outboxHealth.health().getStatus()).isEqualTo(Status.UP);
    }

    protected double gaugeAfterRefresh(String name) {
        outboxMetrics.refresh();
        return gauge(name);
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
