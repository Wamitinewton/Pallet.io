package io.pallet.common.outbox;

import io.micrometer.tracing.Tracer;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

abstract class OutboxIntegrationSupport {

    protected static final String OUTBOX = OutboxTestApplication.SCHEMA + ".outbox_events";

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected DataSource dataSource;

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
    protected OutboxEventTypes eventTypes;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    protected OutboxProperties properties;

    @Autowired
    private OutboxMetrics metrics;

    @BeforeEach
    void cleanOutbox() {
        jdbc.execute("TRUNCATE " + OUTBOX);
    }

    protected JdbcClient jdbcClient() {
        return JdbcClient.create(dataSource);
    }

    protected OutboxRelay newRelay() {
        return newRelay(repository, Clock.systemUTC());
    }

    protected OutboxRelay newRelay(OutboxRepository outboxRepository, Clock clock) {
        return newRelay(outboxRepository, clock, new StaticListableBeanFactory().getBeanProvider(Tracer.class));
    }

    protected OutboxRelay newRelay(OutboxRepository outboxRepository, Clock clock, ObjectProvider<Tracer> tracer) {
        return new OutboxRelay(
                outboxRepository,
                eventTypes,
                publisher,
                jsonMapper,
                transactionManager,
                properties,
                clock,
                metrics,
                tracer);
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
        return jdbc.queryForObject("select status from " + OUTBOX + " where event_id = ?", String.class, eventId);
    }

    protected int attemptsOf(UUID eventId) {
        return jdbc.queryForObject("select attempts from " + OUTBOX + " where event_id = ?", Integer.class, eventId);
    }

    protected void makeEligibleNow(UUID eventId) {
        jdbc.update("update " + OUTBOX + " set next_attempt_at = now() where event_id = ?", eventId);
    }

    protected static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
