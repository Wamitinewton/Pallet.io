package io.pallet.gitintegration.retention;

import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.retention.RetentionSweeps.Sweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nulls delivery payloads every {@code retention.payload-sweep-interval} and runs the other sweeps in
 * {@link RetentionSweeps#DAILY} order every {@code retention.sweep-interval}. A sweep that fails leaves the rest of the
 * run going; its rows wait for the next run.
 */
@Component
class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final RetentionSweeps sweeps;
    private final GitIntegrationProperties.Retention properties;

    RetentionScheduler(RetentionSweeps sweeps, GitIntegrationProperties properties) {
        this.sweeps = sweeps;
        this.properties = properties.retention();
    }

    @Scheduled(fixedDelayString = "${pallet.git.retention.payload-sweep-interval:PT1H}")
    void nullPayloads() {
        if (properties.enabled()) {
            runLogged(Sweep.DELIVERY_PAYLOADS);
        }
    }

    @Scheduled(fixedDelayString = "${pallet.git.retention.sweep-interval:P1D}")
    void sweep() {
        if (properties.enabled()) {
            RetentionSweeps.DAILY.forEach(this::runLogged);
        }
    }

    private void runLogged(Sweep sweep) {
        try {
            sweeps.run(sweep)
                    .ifPresentOrElse(
                            run -> log.info(
                                    "Retention sweep {} finished rows={} batches={} complete={}",
                                    sweep.tag(),
                                    run.rows(),
                                    run.batches(),
                                    run.complete()),
                            () -> log.debug(
                                    "Retention sweep {} skipped, another instance holds the lock", sweep.tag()));
        } catch (RuntimeException e) {
            log.warn(
                    "Retention sweep {} failed: {}: {}",
                    sweep.tag(),
                    e.getClass().getSimpleName(),
                    e.getMessage());
        }
    }
}
