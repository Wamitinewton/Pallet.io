package io.pallet.common.outbox;

import io.micrometer.tracing.Tracer;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Clock;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Builds extra relay instances on the same database, standing in for additional service replicas. */
public final class RelayInstances {

    /** One relay replica; {@link #tick()} runs a single poll cycle. */
    public interface Replica {
        void tick();
    }

    private RelayInstances() {}

    public static Replica create(ApplicationContext context) {
        OutboxRelay relay = new OutboxRelay(
                context.getBean(OutboxRepository.class),
                context.getBean(OutboxEventTypes.class),
                context.getBean(PlatformEventPublisher.class),
                context.getBean(JsonMapper.class),
                context.getBean(PlatformTransactionManager.class),
                context.getBean(OutboxProperties.class),
                Clock.systemUTC(),
                context.getBean(OutboxMetrics.class),
                context.getBeanProvider(Tracer.class));
        return relay::tick;
    }

    public static String metric(ApplicationContext context, String name) {
        return context.getBean(OutboxProperties.class).metric(name);
    }
}
