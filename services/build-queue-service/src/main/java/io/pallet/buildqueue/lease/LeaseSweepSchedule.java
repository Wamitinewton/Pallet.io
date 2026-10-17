package io.pallet.buildqueue.lease;

import io.pallet.buildqueue.config.BuildQueueProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs a sweep every {@code lease.sweep-interval} on one virtual thread. A failed sweep is logged and retried on the
 * next interval: every deadline it missed is still in the table.
 */
@Component
class LeaseSweepSchedule implements SmartLifecycle {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(LeaseSweepSchedule.class);

    private final LeaseSweeper sweeper;
    private final BuildQueueProperties properties;

    private volatile boolean running;
    private Thread worker;

    LeaseSweepSchedule(LeaseSweeper sweeper, BuildQueueProperties properties) {
        this.sweeper = sweeper;
        this.properties = properties;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.lease().sweeperEnabled();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofVirtual().name("lease-sweeper").start(this::loop);
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
                log.warn("Lease sweeper did not stop within {}", STOP_TIMEOUT);
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
                Thread.sleep(properties.lease().sweepInterval());
                if (running) {
                    sweepOnce();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sweepOnce() {
        try {
            sweeper.sweep();
        } catch (RuntimeException e) {
            log.warn("Lease sweep failed; retrying in {}", properties.lease().sweepInterval(), e);
        }
    }
}
