package io.pallet.buildqueue.retention;

import io.pallet.buildqueue.config.BuildQueueProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs retention every {@code retention.interval} on one virtual thread. A failed run is logged and retried on the
 * next interval: every row it missed is still eligible.
 */
@Component
class RetentionSchedule implements SmartLifecycle {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(RetentionSchedule.class);

    private final RetentionSweeps sweeps;
    private final BuildQueueProperties properties;

    private volatile boolean running;
    private Thread worker;

    RetentionSchedule(RetentionSweeps sweeps, BuildQueueProperties properties) {
        this.sweeps = sweeps;
        this.properties = properties;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.retention().enabled();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofVirtual().name("retention-sweeps").start(this::loop);
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
                log.warn("Retention sweeps did not stop within {}", STOP_TIMEOUT);
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
        try {
            while (running) {
                Thread.sleep(properties.retention().interval());
                if (running) {
                    runOnce();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runOnce() {
        try {
            sweeps.run();
        } catch (RuntimeException e) {
            log.warn(
                    "Retention run failed; retrying in {}",
                    properties.retention().interval(),
                    e);
        }
    }
}
