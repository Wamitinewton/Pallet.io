package io.pallet.buildqueue.lease;

import io.pallet.buildqueue.build.BuildEvents;
import io.pallet.buildqueue.build.BuildTransitions;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.config.SchedulingLocks;
import io.pallet.buildqueue.dispatch.DispatchSignal;
import io.pallet.buildqueue.observability.BuildSpans;
import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.common.outbox.OutboxWriter;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/** Sweepers as another instance would run them, sharing the context's database, locks and broker. */
final class LeaseSweepers {

    private LeaseSweepers() {}

    /** A sweeper on an instance configured with {@code dispatch.paused}. */
    static LeaseSweeper withDispatchPaused(ApplicationContext context) {
        return new LeaseSweeper(
                withDispatchPaused(context.getBean(BuildQueueProperties.class)),
                context.getBean(SchedulingLocks.class),
                context.getBean(BackboneReachability.class),
                context.getBean(LeaseSweeperState.class),
                context.getBean(BuildTransitions.class),
                context.getBean(BuildEvents.class),
                context.getBean(OutboxWriter.class),
                context.getBean(DispatchSignal.class),
                context.getBean(BuildSpans.class),
                context.getBean(QueueMetrics.class),
                context.getBean(TransactionTemplate.class));
    }

    private static BuildQueueProperties withDispatchPaused(BuildQueueProperties properties) {
        BuildQueueProperties.Dispatch dispatch = properties.dispatch();
        return new BuildQueueProperties(
                new BuildQueueProperties.Dispatch(
                        dispatch.globalConcurrency(),
                        dispatch.batchSize(),
                        dispatch.pollInterval(),
                        dispatch.startTimeout(),
                        dispatch.maxAttempts(),
                        true,
                        dispatch.wakeupEnabled()),
                properties.build(),
                properties.lease(),
                properties.queue(),
                properties.limits(),
                properties.retention(),
                properties.observability());
    }
}
