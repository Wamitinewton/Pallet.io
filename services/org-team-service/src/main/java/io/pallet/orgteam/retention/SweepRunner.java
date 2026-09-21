package io.pallet.orgteam.retention;

import io.micrometer.core.instrument.Timer;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.retention.SweepLock.Sweep;
import java.util.function.IntUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SweepRunner {

    private static final Logger log = LoggerFactory.getLogger(SweepRunner.class);

    private final TransactionTemplate transaction;
    private final SweepLock lock;
    private final OrgTeamMetrics metrics;
    private final int batchSize;

    SweepRunner(
            PlatformTransactionManager transactionManager,
            SweepLock lock,
            OrgTeamMetrics metrics,
            OrgTeamProperties properties) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.lock = lock;
        this.metrics = metrics;
        this.batchSize = properties.retention().sweepBatchSize();
    }

    /**
     * Runs {@code batch} in one transaction per iteration, under the sweep's advisory lock, until a batch
     * comes back short or another replica holds the lock.
     *
     * @return rows affected across all batches
     */
    public long run(Sweep sweep, IntUnaryOperator batch) {
        Timer.Sample sample = metrics.startTimer();
        long total = 0;
        try {
            while (true) {
                Integer affected =
                        transaction.execute(status -> lock.tryAcquire(sweep) ? batch.applyAsInt(batchSize) : null);
                if (affected == null) {
                    break;
                }
                total += affected;
                count(sweep, affected);
                if (affected < batchSize) {
                    break;
                }
            }
        } finally {
            metrics.stopSweepTimer(sample, sweep.metricTag());
        }
        if (total > 0) {
            log.info("Sweep {} affected {} rows", sweep.metricTag(), total);
        }
        return total;
    }

    void count(Sweep sweep, long rows) {
        metrics.sweepRows(sweep.metricTag(), rows);
    }
}
