package io.pallet.buildqueue.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A dedicated {@code LISTEN} connection outside the pool, the way the dispatcher holds one. */
public final class PgNotifications implements AutoCloseable {

    private final Connection connection;

    public PgNotifications(PostgreSQLContainer postgres, String channel) {
        try {
            connection =
                    DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            try (Statement statement = connection.createStatement()) {
                statement.execute("LISTEN " + channel);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits up to {@code wait} for at least one notification and returns every one delivered by then. */
    public List<String> await(Duration wait) {
        try {
            PGNotification[] delivered =
                    connection.unwrap(PGConnection.class).getNotifications(Math.toIntExact(wait.toMillis()));
            List<String> channels = new ArrayList<>();
            if (delivered != null) {
                for (PGNotification notification : delivered) {
                    channels.add(notification.getName());
                }
            }
            return channels;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
