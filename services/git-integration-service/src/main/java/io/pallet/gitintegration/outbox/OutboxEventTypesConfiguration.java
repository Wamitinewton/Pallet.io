package io.pallet.gitintegration.outbox;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.outbox.OutboxEventTypes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OutboxEventTypesConfiguration {

    @Bean
    public OutboxEventTypes outboxEventTypes() {
        return OutboxEventTypes.of(GitPushReceived.class, NotificationRequested.class, AuditEventRecorded.class);
    }
}
