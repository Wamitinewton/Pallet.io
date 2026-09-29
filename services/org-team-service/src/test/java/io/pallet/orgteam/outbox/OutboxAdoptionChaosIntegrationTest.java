package io.pallet.orgteam.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.outbox.OutboxProperties;
import io.pallet.common.outbox.OutboxWriter;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.support.TopicProbe;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
@TestPropertySource(properties = "pallet.outbox.enabled=true")
class OutboxAdoptionChaosIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration QUIET = Duration.ofSeconds(2);
    private static final long ORG_TEAM_RELAY_LOCK = 7305121408L;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JsonMapper jsonMapper;

    private final List<String> orgIds = new ArrayList<>();

    @BeforeEach
    void installOutboxFault() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS outbox_chaos");
        jdbc.execute("CREATE TABLE IF NOT EXISTS outbox_chaos.failing_orgs (org_id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION outbox_chaos.refuse_listed_orgs() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM outbox_chaos.failing_orgs WHERE org_id = NEW.org_id) THEN
                        RAISE EXCEPTION 'outbox chaos: insert refused';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS outbox_chaos_refuse ON org_team.outbox_events");
        jdbc.execute("""
                CREATE TRIGGER outbox_chaos_refuse BEFORE INSERT ON org_team.outbox_events
                FOR EACH ROW EXECUTE FUNCTION outbox_chaos.refuse_listed_orgs()
                """);
    }

    @AfterEach
    void removeOutboxFaultAndCleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS outbox_chaos_refuse ON org_team.outbox_events");
        jdbc.execute("DROP SCHEMA IF EXISTS outbox_chaos CASCADE");
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.memberships WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.organizations WHERE org_id = ?", orgId);
        });
    }

    @Test
    void theServiceRunsTheSharedRelayUnderItsOwnSchemaMetricsAndLock() {
        assertThat(properties.schema()).isEqualTo("org_team");
        assertThat(properties.metricsPrefix()).isEqualTo("orgteam");
        assertThat(properties.advisoryLockKey()).isEqualTo(ORG_TEAM_RELAY_LOCK);
    }

    @Test
    void aRefusedOutboxInsertRollsBackTheOrgItsOwnerAndTheInboxClaimAndTheRedeliveryAppliesOnce() {
        String orgId = newOrgId();
        OrgProvisioned event = OrgProvisioned.of(
                orgId,
                "Chaos " + orgId,
                "chaos-" + orgId.substring(4, 12),
                "user-" + UUID.randomUUID(),
                "owner@example.com",
                "Owner");
        double failedBefore = failedDeliveries();
        jdbc.update("INSERT INTO outbox_chaos.failing_orgs (org_id) VALUES (?)", orgId);

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), jsonMapper, Topics.ORG_MEMBER_ADDED)) {
            publisher.publish(event);

            await().atMost(WAIT)
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> assertThat(failedDeliveries()).isGreaterThan(failedBefore));
            assertThat(rows("organizations", orgId)).as("organization").isZero();
            assertThat(rows("memberships", orgId)).as("owner membership").isZero();
            assertThat(rows("outbox_events", orgId)).as("outbox rows").isZero();
            assertThat(claims(event.eventId())).as("inbox claim").isZero();

            jdbc.update("DELETE FROM outbox_chaos.failing_orgs WHERE org_id = ?", orgId);

            await().atMost(WAIT)
                    .untilAsserted(
                            () -> assertThat(rows("organizations", orgId)).isEqualTo(1));
            assertThat(rows("memberships", orgId)).isEqualTo(1);
            assertThat(claims(event.eventId())).isEqualTo(1);
            assertThat(outboxRows(orgId, OrgMemberAdded.TYPE)).isEqualTo(1);
            assertThat(probe.awaitCount(orgId, 1)).hasSize(1);
            assertThat(probe.observe(orgId, QUIET)).as("published exactly once").hasSize(1);
        }
    }

    @Test
    void theRelayYieldsWhileAnotherInstanceHoldsTheServicesLockKey() throws Exception {
        String orgId = newOrgId();
        OrgMemberAdded event = OrgMemberAdded.of(orgId, "user-" + UUID.randomUUID(), "member@example.com");

        try (Connection otherInstance = dataSource.getConnection()) {
            lock(otherInstance, "SELECT pg_advisory_lock(?)");
            try {
                transaction.executeWithoutResult(status -> writer.append(event));

                await().during(QUIET)
                        .atMost(QUIET.multipliedBy(2))
                        .untilAsserted(
                                () -> assertThat(statusOf(event.eventId())).isEqualTo("PENDING"));
            } finally {
                lock(otherInstance, "SELECT pg_advisory_unlock(?)");
            }
        }

        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(statusOf(event.eventId())).isEqualTo("PUBLISHED"));
    }

    private static void lock(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, ORG_TEAM_RELAY_LOCK);
            statement.execute();
        }
    }

    private String newOrgId() {
        String orgId = "org-" + UUID.randomUUID();
        orgIds.add(orgId);
        return orgId;
    }

    private double failedDeliveries() {
        Counter counter = registry.find(MetricsCatalog.EVENTS_FAILED)
                .tag(MetricsCatalog.TAG_LISTENER, MetricsCatalog.LISTENER_ORG_PROVISIONED)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private int rows(String table, String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team." + table + " WHERE org_id = ?", Integer.class, orgId);
    }

    private int outboxRows(String orgId, String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ? AND event_type = ?",
                Integer.class,
                orgId,
                eventType);
    }

    private int claims(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.processed_events WHERE event_id = ? AND consumer = ?",
                Integer.class,
                eventId,
                MetricsCatalog.LISTENER_ORG_PROVISIONED);
    }

    private String statusOf(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM org_team.outbox_events WHERE event_id = ?", String.class, eventId);
    }
}
