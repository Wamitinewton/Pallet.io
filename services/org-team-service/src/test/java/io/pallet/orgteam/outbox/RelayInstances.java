package io.pallet.orgteam.outbox;

import io.micrometer.tracing.Tracer;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.orgteam.config.OrgTeamProperties;
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
                context.getBean(EventTypeRegistry.class),
                context.getBean(PlatformEventPublisher.class),
                context.getBean(JsonMapper.class),
                context.getBean(PlatformTransactionManager.class),
                context.getBean(OrgTeamProperties.class),
                Clock.systemUTC(),
                context.getBean(OutboxMetrics.class),
                context.getBeanProvider(Tracer.class));
        return relay::tick;
    }
}
