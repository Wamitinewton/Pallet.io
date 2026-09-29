package io.pallet.gitintegration.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.retention.RetentionSweeps.Run;
import io.pallet.gitintegration.retention.RetentionSweeps.Sweep;
import io.pallet.gitintegration.support.ReadModelFixtures;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class RetentionConcurrencyIntegrationTest {

    private static final Duration OLD = Duration.ofDays(31);
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private RetentionSweeps sweeps;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private ReadModelFixtures fixtures;
    private RetentionRows rows;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        rows = new RetentionRows(jdbc);
    }

    @AfterEach
    void tearDown() {
        rows.removeAll();
        fixtures.cleanUp();
    }

    @Test
    void aSweepSkipsARowTheDeliveryProcessorHoldsAndFinishesWithoutWaiting() throws SQLException {
        UUID held = rows.delivery("PROCESSED", OLD);
        UUID free = rows.delivery("PROCESSED", OLD);
        UUID heldPayload = rows.delivery("PROCESSED", OLD);

        try (Connection holder = lockRows(
                "SELECT 1 FROM git_integration.webhook_deliveries WHERE delivery_id IN (?, ?) FOR UPDATE",
                held,
                heldPayload)) {
            long started = System.nanoTime();
            Run deleted = sweeps.run(Sweep.DELIVERIES).orElseThrow();
            Run nulled = sweeps.run(Sweep.DELIVERY_PAYLOADS).orElseThrow();
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(deleted.complete()).isTrue();
            assertThat(nulled.complete()).isTrue();
            assertThat(took).isLessThan(Duration.ofSeconds(5));
            assertThat(rows.deliveryExists(free)).isFalse();
            assertThat(rows.deliveryExists(held)).isTrue();
            assertThat(rows.payloadNulled(heldPayload)).isFalse();
            holder.rollback();
        }

        sweeps.run(Sweep.DELIVERIES).orElseThrow();

        assertThat(rows.deliveryExists(held)).isFalse();
        assertThat(rows.deliveryExists(heldPayload)).isFalse();
    }

    @Test
    void whileOneInstanceRunsASweepAnotherSkipsIt() throws Exception {
        String orgId = fixtures.newOrg();
        UUID eventId = rows.outboxEvent(orgId, OLD, OLD);

        CompletableFuture<Optional<Run>> first;
        try (Connection holder =
                lockRows("SELECT 1 FROM git_integration.outbox_events WHERE event_id = ? FOR UPDATE", eventId)) {
            first = CompletableFuture.supplyAsync(() -> sweeps.run(Sweep.OUTBOX));
            await().atMost(WAIT).until(this::outboxSweepIsWaitingOnALock);

            assertThat(sweeps.run(Sweep.OUTBOX)).isEmpty();

            holder.rollback();
        }

        Optional<Run> run = first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(run).hasValueSatisfying(r -> assertThat(r.complete()).isTrue());
        assertThat(rows.outboxEventExists(eventId)).isFalse();
    }

    @Test
    void aSweepThatCannotTakeALockGivesUpAtTheLockTimeoutInsteadOfQueueing() throws SQLException {
        String orgId = fixtures.newOrg();
        UUID eventId = rows.outboxEvent(orgId, OLD, OLD);

        try (Connection holder =
                lockRows("SELECT 1 FROM git_integration.outbox_events WHERE event_id = ? FOR UPDATE", eventId)) {
            Run run = sweeps.run(Sweep.OUTBOX).orElseThrow();

            assertThat(run.complete()).isFalse();
            assertThat(rows.outboxEventExists(eventId)).isTrue();
            holder.rollback();
        }

        assertThat(sweeps.run(Sweep.OUTBOX).orElseThrow().complete()).isTrue();
        assertThat(rows.outboxEventExists(eventId)).isFalse();
    }

    private boolean outboxSweepIsWaitingOnALock() {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                                + " AND query LIKE '%DELETE FROM git_integration.outbox_events%'",
                        Integer.class)
                > 0;
    }

    /** A connection outside the application's pool, so holding it doesn't starve the sweep it is racing. */
    private Connection lockRows(String sql, Object... ids) throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        Connection connection = DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < ids.length; i++) {
                statement.setObject(i + 1, ids[i]);
            }
            statement.executeQuery().close();
        }
        return connection;
    }
}
