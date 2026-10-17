package io.pallet.buildqueue.outbox;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.BuildDispatched;
import io.pallet.common.events.BuildQueued;
import io.pallet.common.outbox.OutboxEventTypes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OutboxEventTypesConfiguration {

    @Bean
    public OutboxEventTypes outboxEventTypes() {
        return OutboxEventTypes.of(
                BuildQueued.class, BuildDispatched.class, BuildCancelled.class, AuditEventRecorded.class);
    }
}
