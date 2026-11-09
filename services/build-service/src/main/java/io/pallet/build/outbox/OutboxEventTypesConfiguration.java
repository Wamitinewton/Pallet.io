package io.pallet.build.outbox;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.BuildFailed;
import io.pallet.common.events.BuildStarted;
import io.pallet.common.events.BuildSucceeded;
import io.pallet.common.outbox.OutboxEventTypes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OutboxEventTypesConfiguration {

    @Bean
    public OutboxEventTypes outboxEventTypes() {
        return OutboxEventTypes.of(
                BuildStarted.class, BuildSucceeded.class, BuildFailed.class, AuditEventRecorded.class);
    }
}
