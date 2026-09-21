package io.pallet.orgteam.outbox.chaos;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Faults injected through real Postgres mechanisms: triggers that raise, killed sessions, held locks. */
final class PostgresFaults {

    enum Statement {
        INSERT("BEFORE INSERT", "TRUE"),
        MARK_PUBLISHED("BEFORE UPDATE", "OLD.status = 'PENDING' AND NEW.status = 'PUBLISHED'");

        private final String timing;
        private final String condition;

        Statement(String timing, String condition) {
            this.timing = timing;
            this.condition = condition;
        }

        String triggerName() {
            return "chaos_fail_" + name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Object INSTALL_LOCK = new Object();
    private static boolean installed;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final long relayLockKey;

    PostgresFaults(JdbcTemplate jdbc, DataSource dataSource, long relayLockKey) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.relayLockKey = relayLockKey;
    }

    /**
     * Makes every matching write to the outbox table raise, as a full disk or a failing replica would.
     * {@code orgPattern} is a SQL LIKE pattern over {@code org_id}, so the fault stays scoped to the
     * calling test's rows.
     *
     * <p>Faults are rows in a control table read by permanent triggers, never {@code CREATE}/{@code DROP
     * TRIGGER} at fault time: dropping a trigger needs an exclusive table lock, which queues behind the
     * relay's open batch transaction while the relay's own second connection queues behind the DDL, and
     * Postgres cannot see that cycle.
     */
    Fault failOutboxWrites(Statement statement, String orgPattern) {
        installTriggers();
        UUID faultId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO chaos.faults (id, statement, org_pattern) VALUES (?, ?, ?)",
                faultId,
                statement.name(),
                orgPattern);
        return new RevertibleFault(() -> jdbc.update("DELETE FROM chaos.faults WHERE id = ?", faultId));
    }

    private void installTriggers() {
        synchronized (INSTALL_LOCK) {
            if (installed) {
                return;
            }
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS chaos");
            jdbc.execute("CREATE TABLE IF NOT EXISTS chaos.faults "
                    + "(id UUID PRIMARY KEY, statement TEXT NOT NULL, org_pattern TEXT NOT NULL)");
            jdbc.execute("""
                    CREATE OR REPLACE FUNCTION chaos.inject_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF EXISTS (SELECT 1 FROM chaos.faults f
                                   WHERE f.statement = TG_ARGV[0] AND NEW.org_id LIKE f.org_pattern) THEN
                            RAISE EXCEPTION 'chaos: injected failure' USING ERRCODE = '58030';
                        END IF;
                        RETURN NEW;
                    END
                    $$""");
            for (Statement statement : Statement.values()) {
                Integer existing = jdbc.queryForObject(
                        "SELECT count(*) FROM pg_trigger WHERE tgname = ? "
                                + "AND tgrelid = 'org_team.outbox_events'::regclass",
                        Integer.class,
                        statement.triggerName());
                if (existing == 0) {
                    jdbc.execute("CREATE TRIGGER " + statement.triggerName() + " " + statement.timing
                            + " ON org_team.outbox_events FOR EACH ROW WHEN (" + statement.condition + ") "
                            + "EXECUTE FUNCTION chaos.inject_failure('" + statement.name() + "')");
                }
            }
            installed = true;
        }
    }

    /** Terminates the backend session currently holding the relay's advisory lock. */
    int killRelayLockHolders() {
        return jdbc.query("""
                        SELECT pg_terminate_backend(pid) FROM pg_locks
                        WHERE locktype = 'advisory' AND granted AND objsubid = 1
                          AND classid::bigint = ? AND objid::bigint = ? AND pid <> pg_backend_pid()
                        """, (rs, row) -> rs.getBoolean(1), relayLockKey >>> 32, relayLockKey & 0xFFFFFFFFL)
                .size();
    }

    int relayLockHolders() {
        return jdbc.queryForObject("""
                        SELECT count(DISTINCT pid) FROM pg_locks
                        WHERE locktype = 'advisory' AND granted AND objsubid = 1
                          AND classid::bigint = ? AND objid::bigint = ?
                        """, Integer.class, relayLockKey >>> 32, relayLockKey & 0xFFFFFFFFL);
    }

    /** Takes the relay lock on a dedicated session, so every relay sees itself as standby. */
    HeldLock holdRelayLock() {
        try {
            Connection connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            int pid;
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(" + relayLockKey + ")");
                var rs = statement.executeQuery("SELECT pg_backend_pid()");
                rs.next();
                pid = rs.getInt(1);
            }
            return new HeldLock(connection, pid);
        } catch (SQLException e) {
            throw new IllegalStateException("could not take the relay lock", e);
        }
    }

    boolean terminateBackend(int pid) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pid));
    }

    static final class HeldLock implements Fault {

        private final Connection connection;
        private final int pid;

        private HeldLock(Connection connection, int pid) {
            this.connection = connection;
            this.pid = pid;
        }

        int pid() {
            return pid;
        }

        @Override
        public void close() {
            try {
                connection.rollback();
            } catch (SQLException sessionAlreadyDead) {
                // a killed backend is exactly the outcome some tests want
            } finally {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // returning a dead connection to the pool is best effort
                }
            }
        }
    }
}
