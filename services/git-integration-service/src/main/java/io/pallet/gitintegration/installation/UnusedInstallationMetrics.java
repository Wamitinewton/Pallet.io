package io.pallet.gitintegration.installation;

import static io.pallet.gitintegration.observability.MetricsCatalog.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The unused-installation meters. The gauge is refreshed on its own schedule, so it stays current between sweeps. */
@Component
class UnusedInstallationMetrics {

    private static final Logger log = LoggerFactory.getLogger(UnusedInstallationMetrics.class);

    private final InstallationRepository installations;
    private final Counter uninstalled;
    private final AtomicLong unused = new AtomicLong();

    UnusedInstallationMetrics(MeterRegistry registry, InstallationRepository installations) {
        this.installations = installations;
        this.uninstalled = Counter.builder(INSTALLATIONS_UNINSTALLED)
                .description("Installations Pallet uninstalled from GitHub after their grace period unused")
                .register(registry);
        Gauge.builder(INSTALLATIONS_UNUSED, unused, AtomicLong::get)
                .description("Active installations no org links, waiting out their grace period")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${pallet.git.unused-installation.metrics-interval:PT1M}")
    void refresh() {
        try {
            unused.set(installations.countUnused());
        } catch (DataAccessException e) {
            log.warn(
                    "Could not refresh the unused-installation gauge, keeping the last value: {}",
                    e.getClass().getName());
        }
    }

    void uninstalled() {
        uninstalled.increment();
    }
}
