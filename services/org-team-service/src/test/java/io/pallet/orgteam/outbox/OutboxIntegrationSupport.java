package io.pallet.orgteam.outbox;

import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.orgteam.config.OrgTeamProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

abstract class OutboxIntegrationSupport {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JdbcClient jdbcClient;

    @Autowired
    protected TransactionTemplate transaction;

    @Autowired
    protected OutboxWriter writer;

    @Autowired
    protected OutboxRepository repository;

    @Autowired
    protected KafkaContainer kafka;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    private EventTypeRegistry registry;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    protected OrgTeamProperties properties;

    @Autowired
    private OutboxMetrics metrics;

    @BeforeEach
    void cleanOutbox() {
        jdbc.execute("TRUNCATE org_team.outbox_events");
    }

    protected OutboxRelay newRelay() {
        return newRelay(repository, Clock.systemUTC());
    }

    protected OutboxRelay newRelay(OutboxRepository outboxRepository, Clock clock) {
        return new OutboxRelay(
                outboxRepository,
                registry,
                publisher,
                jsonMapper,
                transactionManager,
                properties,
                clock,
                metrics,
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(io.micrometer.tracing.Tracer.class));
    }

    protected TopicProbe probe(String... topics) {
        return new TopicProbe(kafka.getBootstrapServers(), jsonMapper, topics);
    }

    protected static String newOrgId() {
        return "org-" + UUID.randomUUID();
    }

    protected static OrgMemberAdded memberAdded(String orgId) {
        return OrgMemberAdded.of(orgId, "user-" + UUID.randomUUID(), "member@example.com");
    }

    protected void commit(PlatformEvent... events) {
        transaction.executeWithoutResult(status -> {
            for (PlatformEvent event : events) {
                writer.append(event);
            }
        });
    }

    protected String statusOf(UUID eventId) {
        return jdbc.queryForObject(
                "select status from org_team.outbox_events where event_id = ?", String.class, eventId);
    }

    protected int attemptsOf(UUID eventId) {
        return jdbc.queryForObject(
                "select attempts from org_team.outbox_events where event_id = ?", Integer.class, eventId);
    }

    protected void makeEligibleNow(UUID eventId) {
        jdbc.update("update org_team.outbox_events set next_attempt_at = now() where event_id = ?", eventId);
    }

    protected static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
