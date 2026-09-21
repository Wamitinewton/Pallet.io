package io.pallet.orgteam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.github.dockerjava.api.DockerClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.outbox.OutboxMetrics;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.outbox.TopicProbe;
import io.pallet.orgteam.outbox.TopicProbe.Received;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
@TestPropertySource(
        properties = {
            "pallet.orgteam.outbox.enabled=true",
            "pallet.orgteam.outbox.broker-backoff-max=PT1S",
            "pallet.orgteam.outbox.row-backoff-max=PT1S",
            "management.endpoint.health.show-details=always"
        })
class ChaosIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Duration LOCK_HELD = Duration.ofSeconds(2);
    private static final String READINESS = "/actuator/health/readiness";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutboxMetrics outboxMetrics;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private io.pallet.orgteam.config.OrgTeamProperties properties;

    @MockitoSpyBean
    private OrgTeamMetrics metrics;

    private final List<String> orgIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.memberships WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.organizations WHERE org_id = ?", orgId);
        });
        orgIds.clear();
    }

    @Test
    void aBrokerOutageStallsTheOutboxWithoutTakingReadinessDownAndDrainsOnRecovery() throws Exception {
        String orgId = newOrgId();
        readinessIsUp();
        DockerClient docker = kafka.getDockerClient();
        List<OrgMemberAdded> events = List.of(memberAdded(orgId), memberAdded(orgId), memberAdded(orgId));
        double brokerFailures =
                counter(MetricsCatalog.OUTBOX_PUBLISH_FAILURES, MetricsCatalog.TAG_KIND, MetricsCatalog.KIND_BROKER);

        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            commit(events);
            await().atMost(WAIT).untilAsserted(() -> {
                outboxMetrics.refresh();
                assertThat(gauge(MetricsCatalog.OUTBOX_OLDEST_PENDING_AGE)).isGreaterThan(2);
                assertThat(counter(
                                MetricsCatalog.OUTBOX_PUBLISH_FAILURES,
                                MetricsCatalog.TAG_KIND,
                                MetricsCatalog.KIND_BROKER))
                        .isGreaterThan(brokerFailures);
            });
            assertThat(gauge(MetricsCatalog.OUTBOX_PENDING)).isGreaterThanOrEqualTo(events.size());
            readinessIsUp();
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), jsonMapper, Topics.ORG_MEMBER_ADDED)) {
            await().atMost(WAIT)
                    .untilAsserted(() -> assertThat(pendingFor(orgId)).isZero());
            assertThat(probe.awaitCount(orgId, events.size()).stream()
                            .map(Received::eventId)
                            .distinct())
                    .containsExactlyElementsOf(
                            events.stream().map(OrgMemberAdded::eventId).toList());
        }
        outboxMetrics.refresh();
        await().atMost(WAIT).untilAsserted(() -> {
            outboxMetrics.refresh();
            assertThat(gauge(MetricsCatalog.OUTBOX_OLDEST_PENDING_AGE)).isZero();
        });
    }

    @Test
    void aPoisonRowParksAndHoldsOnlyItsOwnOrgWhileOthersKeepPublishing() {
        String poisoned = newOrgId();
        String healthy = newOrgId();
        double parkedBefore = gaugeAfterRefresh(MetricsCatalog.OUTBOX_PARKED);
        double rowFailures =
                counter(MetricsCatalog.OUTBOX_PUBLISH_FAILURES, MetricsCatalog.TAG_KIND, MetricsCatalog.KIND_ROW);
        jdbc.update(
                "INSERT INTO org_team.outbox_events (event_id, org_id, event_type, payload) "
                        + "VALUES (?, ?, 'bogus.event', '{}'::jsonb)",
                UUID.randomUUID(),
                poisoned);
        OrgMemberAdded behindPoison = memberAdded(poisoned);
        OrgMemberAdded unrelated = memberAdded(healthy);
        commit(List.of(behindPoison, unrelated));

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(statusOf(unrelated.eventId())).isEqualTo("PUBLISHED");
            outboxMetrics.refresh();
            assertThat(gauge(MetricsCatalog.OUTBOX_PARKED)).isEqualTo(parkedBefore + 1);
        });

        assertThat(statusOf(behindPoison.eventId())).isEqualTo("PENDING");
        assertThat(counter(MetricsCatalog.OUTBOX_PUBLISH_FAILURES, MetricsCatalog.TAG_KIND, MetricsCatalog.KIND_ROW))
                .isGreaterThan(rowFailures);
        jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ? AND event_type = 'bogus.event'", poisoned);
        await().atMost(WAIT)
                .untilAsserted(
                        () -> assertThat(statusOf(behindPoison.eventId())).isEqualTo("PUBLISHED"));
    }

    @Test
    void aConsumerCrashAfterItsEffectLeavesNoPartialStateAndTheRedeliverySucceeds() {
        String orgId = newOrgId();
        AtomicInteger deliveries = new AtomicInteger();
        doAnswer(invocation -> {
                    Object result = invocation.callRealMethod();
                    if (deliveries.incrementAndGet() == 1) {
                        throw new IllegalStateException("simulated crash after the effect");
                    }
                    return result;
                })
                .when(metrics)
                .eventProcessed(eq(MetricsCatalog.LISTENER_ORG_PROVISIONED));
        double failed = counter(
                MetricsCatalog.EVENTS_FAILED, MetricsCatalog.TAG_LISTENER, MetricsCatalog.LISTENER_ORG_PROVISIONED);
        double processed = counter(
                MetricsCatalog.EVENTS_PROCESSED, MetricsCatalog.TAG_LISTENER, MetricsCatalog.LISTENER_ORG_PROVISIONED);

        publisher.publish(OrgProvisioned.of(
                orgId, "Chaos Org", "chaos-" + orgId, "owner-" + orgId, "owner@example.com", "Owner"));

        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(count("organizations", orgId)).isEqualTo(1));
        assertThat(deliveries.get()).isEqualTo(2);
        assertThat(count("memberships", orgId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ? AND event_type = ?",
                        Integer.class,
                        orgId,
                        OrgMemberAdded.TYPE))
                .isEqualTo(1);
        assertThat(counter(
                        MetricsCatalog.EVENTS_FAILED,
                        MetricsCatalog.TAG_LISTENER,
                        MetricsCatalog.LISTENER_ORG_PROVISIONED))
                .isEqualTo(failed + 1);
        assertThat(counter(
                        MetricsCatalog.EVENTS_PROCESSED,
                        MetricsCatalog.TAG_LISTENER,
                        MetricsCatalog.LISTENER_ORG_PROVISIONED))
                .isEqualTo(processed + 1);
        orgIds.add(orgId);
    }

    @Test
    void aStandbyRelayTakesOverWhenTheLockHolderDiesWithoutLosingOrReorderingEvents() throws Exception {
        String orgId = newOrgId();
        List<OrgMemberAdded> events = List.of(
                memberAdded(orgId), memberAdded(orgId), memberAdded(orgId), memberAdded(orgId), memberAdded(orgId));

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), jsonMapper, Topics.ORG_MEMBER_ADDED)) {
            Connection holder = dataSource.getConnection();
            try {
                holder.setAutoCommit(false);
                holder.createStatement()
                        .execute("SELECT pg_advisory_xact_lock("
                                + properties.outbox().advisoryLockKey() + ")");
                commit(events);

                assertThat(probe.observe(orgId, LOCK_HELD)).isEmpty();
                assertThat(pendingFor(orgId)).isEqualTo(events.size());
                assertThat(gauge(MetricsCatalog.OUTBOX_RELAY_ACTIVE)).isZero();
            } finally {
                release(holder);
            }

            await().atMost(WAIT)
                    .untilAsserted(() -> assertThat(pendingFor(orgId)).isZero());
            assertThat(probe.awaitCount(orgId, events.size()).stream()
                            .map(Received::eventId)
                            .toList())
                    .containsExactlyElementsOf(
                            events.stream().map(OrgMemberAdded::eventId).toList());
            assertThat(gauge(MetricsCatalog.OUTBOX_RELAY_ACTIVE)).isEqualTo(1);
        }
    }

    private void readinessIsUp() throws Exception {
        MvcResult result = mvc.perform(get(READINESS)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private void commit(List<OrgMemberAdded> events) {
        orgIds.add(events.getFirst().orgId());
        transaction.executeWithoutResult(status -> events.forEach(writer::append));
    }

    private static void release(Connection holder) throws SQLException {
        holder.rollback();
        holder.close();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double gaugeAfterRefresh(String name) {
        outboxMetrics.refresh();
        return gauge(name);
    }

    private double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private int pendingFor(String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ? AND status <> 'PUBLISHED'",
                Integer.class,
                orgId);
    }

    private String statusOf(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM org_team.outbox_events WHERE event_id = ?", String.class, eventId);
    }

    private int count(String table, String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team." + table + " WHERE org_id = ?", Integer.class, orgId);
    }

    private static String newOrgId() {
        return "org-" + UUID.randomUUID();
    }

    private static OrgMemberAdded memberAdded(String orgId) {
        return OrgMemberAdded.of(orgId, "user-" + UUID.randomUUID(), "member@example.com");
    }
}
