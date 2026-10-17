package io.pallet.buildqueue.dispatch;

import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.dispatch.Dispatcher.Round;
import io.pallet.buildqueue.observability.QueueMetrics;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.PGProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs dispatcher rounds when a commit says there may be work, and on {@code dispatch.poll-interval} as a safety net.
 * Holds one {@code LISTEN} connection opened straight from the driver, outside the pool: a pooled connection is handed
 * to other work between uses, and a pooler in transaction mode drops {@code LISTEN} registrations silently.
 *
 * <p>Notifications that arrive during a round are delivered together on the next wait, so a burst costs one extra
 * round, not one per commit. Every (re)connection runs a round too, since notifications sent while nobody was
 * listening are gone.
 */
@Component
class DispatchWakeup implements SmartLifecycle {

    static final String APPLICATION_NAME = "build-queue-dispatch-wakeup";

    private static final String LISTEN = "LISTEN " + DispatchSignal.CHANNEL;
    private static final Duration FIRST_RECONNECT_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RECONNECT_DELAY = Duration.ofSeconds(10);
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(DispatchWakeup.class);

    private final Dispatcher dispatcher;
    private final BuildQueueProperties properties;
    private final JdbcConnectionDetails connectionDetails;
    private final QueueMetrics metrics;

    private volatile boolean running;
    private Thread worker;

    private Connection connection;
    private boolean everConnected;
    private Duration reconnectDelay = FIRST_RECONNECT_DELAY;
    private long nextConnectAttemptNanos;

    DispatchWakeup(
            Dispatcher dispatcher,
            BuildQueueProperties properties,
            JdbcConnectionDetails connectionDetails,
            QueueMetrics metrics) {
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.connectionDetails = connectionDetails;
        this.metrics = metrics;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.dispatch().wakeupEnabled();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofVirtual().name("dispatch-wakeup").start(this::loop);
    }

    @Override
    public synchronized void stop() {
        Thread stopping = worker;
        if (!running || stopping == null) {
            return;
        }
        running = false;
        stopping.interrupt();
        try {
            if (!stopping.join(STOP_TIMEOUT)) {
                log.warn("Dispatch wakeup did not stop within {}", STOP_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        nextConnectAttemptNanos = System.nanoTime();
        try {
            while (running) {
                awaitWakeup(properties.dispatch().pollInterval());
                if (running) {
                    drain();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            disconnect();
        }
    }

    /** Returns on a notification, when the poll interval passes, or when a (re)connection means one may be lost. */
    private void awaitWakeup(Duration pollInterval) throws InterruptedException {
        if (connection == null && System.nanoTime() - nextConnectAttemptNanos >= 0 && connect()) {
            return;
        }
        if (connection == null) {
            long untilReconnect = Math.max(0, nextConnectAttemptNanos - System.nanoTime());
            TimeUnit.NANOSECONDS.sleep(Math.min(pollInterval.toNanos(), untilReconnect));
            return;
        }
        try {
            int timeoutMillis = Math.toIntExact(Math.max(1, pollInterval.toMillis()));
            PGNotification[] notifications =
                    connection.unwrap(PGConnection.class).getNotifications(timeoutMillis);
            if ((notifications == null || notifications.length == 0)
                    && !connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                lost(null);
            }
        } catch (SQLException e) {
            if (Thread.interrupted() || !running) {
                throw new InterruptedException("Dispatch wakeup stopping");
            }
            lost(e);
        }
    }

    private void drain() {
        try {
            Round round;
            do {
                round = dispatcher.runRound();
            } while (running && round.batchFull());
        } catch (RuntimeException e) {
            log.warn("Dispatch round failed; retrying on the next wakeup", e);
        }
    }

    private boolean connect() {
        Properties connectionProperties = new Properties();
        setIfPresent(connectionProperties, PGProperty.USER.getName(), connectionDetails.getUsername());
        setIfPresent(connectionProperties, PGProperty.PASSWORD.getName(), connectionDetails.getPassword());
        connectionProperties.setProperty(PGProperty.APPLICATION_NAME.getName(), APPLICATION_NAME);
        connectionProperties.setProperty(PGProperty.TCP_KEEP_ALIVE.getName(), Boolean.TRUE.toString());
        Connection opened = null;
        try {
            opened = DriverManager.getConnection(connectionDetails.getJdbcUrl(), connectionProperties);
            try (Statement statement = opened.createStatement()) {
                statement.execute(LISTEN);
            }
        } catch (SQLException e) {
            close(opened);
            log.debug("Dispatch LISTEN connection unavailable; next attempt in {}", reconnectDelay, e);
            scheduleReconnect();
            return false;
        }
        connection = opened;
        reconnectDelay = FIRST_RECONNECT_DELAY;
        metrics.listenConnected(true);
        if (everConnected) {
            log.info("Dispatch LISTEN connection re-established");
        }
        everConnected = true;
        return true;
    }

    /** Logged once per outage; rounds keep running on the poll interval until a reconnect succeeds. */
    private void lost(SQLException cause) {
        log.warn(
                "Dispatch LISTEN connection lost; polling every {} until it reconnects",
                properties.dispatch().pollInterval(),
                cause);
        disconnect();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        nextConnectAttemptNanos = System.nanoTime() + reconnectDelay.toNanos();
        Duration doubled = reconnectDelay.multipliedBy(2);
        reconnectDelay = doubled.compareTo(MAX_RECONNECT_DELAY) > 0 ? MAX_RECONNECT_DELAY : doubled;
    }

    private void disconnect() {
        close(connection);
        connection = null;
        metrics.listenConnected(false);
    }

    private static void close(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Closing the dispatch LISTEN connection failed", e);
        }
    }

    private static void setIfPresent(Properties properties, String key, String value) {
        if (value != null) {
            properties.setProperty(key, value);
        }
    }
}
