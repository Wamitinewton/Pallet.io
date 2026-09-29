package io.pallet.gitintegration.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * One Postgres advisory lock per scheduled job, so across every instance at most one runs it at a time. The lock is a
 * {@code pg_try_advisory_xact_lock} on a connection of its own that writes and row-locks nothing, so the job's GitHub
 * calls and its own short transactions run outside it, and an instance that dies releases the lock with its
 * connection.
 */
@Component
public class SchedulingLocks {

    /** Every scheduled job's lock key, next to the outbox relay's {@code 7305121409}. */
    public enum Job {
        REPOSITORY_SYNC(7_305_121_410L),
        REDELIVERY_SWEEPER(7_305_121_411L),
        HEAD_RECONCILER(7_305_121_412L),
        ACCESS_REVERIFIER(7_305_121_413L),
        UNUSED_INSTALLATION_SWEEP(7_305_121_414L),
        CHECK_RUN_REPORTER(7_305_121_415L),
        RETENTION_DELIVERY_PAYLOADS(7_305_121_416L),
        RETENTION_DELIVERIES(7_305_121_417L),
        RETENTION_OUTBOX(7_305_121_418L),
        RETENTION_INBOX(7_305_121_419L),
        RETENTION_AUTHORIZATION_STATES(7_305_121_420L),
        RETENTION_MANUAL_BUILD_REQUESTS(7_305_121_421L),
        RETENTION_CHECK_RUNS(7_305_121_422L),
        RETENTION_REPO_LINKS(7_305_121_423L),
        RETENTION_INSTALLATION_LINKS(7_305_121_424L),
        RETENTION_APPS(7_305_121_425L),
        RETENTION_INSTALLATIONS(7_305_121_426L),
        RETENTION_DELETED_ORGS(7_305_121_427L);

        private final long key;

        Job(long key) {
            this.key = key;
        }

        public long key() {
            return key;
        }
    }

    private final DataSource dataSource;

    SchedulingLocks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return {@code false}, without running {@code work}, when another instance holds the job's lock */
    public boolean runExclusively(Job job, Runnable work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!tryLock(connection, job)) {
                    return false;
                }
                work.run();
                return true;
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
