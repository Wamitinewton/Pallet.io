package io.pallet.buildqueue.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One Postgres advisory lock per singleton job, so across every instance at most one runs it at a time. The lock is a
 * {@code pg_try_advisory_xact_lock}, released when its transaction ends, so a crashed instance never holds a job
 * hostage.
 */
@Component
public class SchedulingLocks {

    /**
     * Advisory locks are database-wide, so these keys stay clear of every other service's, including each outbox
     * relay's {@code pallet.outbox.advisory-lock-key}.
     */
    public enum Job {
        DISPATCH(7_305_121_601L),
        LEASE_SWEEP(7_305_121_602L),
        RETENTION(7_305_121_603L);

        private final long key;

        Job(long key) {
            this.key = key;
        }

        public long key() {
            return key;
        }
    }

    private static final String TRY_LOCK = "SELECT pg_try_advisory_xact_lock(:key)";

    private final JdbcClient jdbc;
    private final DataSource dataSource;

    SchedulingLocks(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /**
     * Takes the job's lock on the caller's transaction, for a job that is one transaction.
     *
     * @return {@code false} when another transaction holds the job's lock
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLock(Job job) {
        return jdbc.sql(TRY_LOCK).param("key", job.key()).query(Boolean.class).single();
    }

    /**
     * Runs {@code work} holding the job's lock on a connection of its own, for a job made of many short transactions.
     * That connection writes and row-locks nothing, and is rolled back afterwards, which releases the lock.
     *
     * @return empty, without running {@code work}, when another instance holds the job's lock
     */
    public <T> Optional<T> runExclusively(Job job, Supplier<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                return tryLock(connection, job) ? Optional.of(work.get()) : Optional.empty();
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("Scheduling lock " + job + " unavailable", e);
        }
    }

    private static boolean tryLock(Connection connection, Job job) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
            statement.setLong(1, job.key());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
