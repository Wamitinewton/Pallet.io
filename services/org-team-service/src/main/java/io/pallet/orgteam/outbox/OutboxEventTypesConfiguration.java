package io.pallet.orgteam.outbox;

import io.pallet.common.events.AppCreated;
import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.OrgInviteRejected;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgMemberRemoved;
import io.pallet.common.events.OrgMemberRoleChanged;
import io.pallet.common.outbox.OutboxEventTypes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OutboxEventTypesConfiguration {

    @Bean
    public OutboxEventTypes outboxEventTypes() {
        return OutboxEventTypes.of(
                OrgMemberAdded.class,
                OrgMemberRemoved.class,
                OrgMemberRoleChanged.class,
                OrgDeleted.class,
                OrgInviteRejected.class,
                AppCreated.class,
                AppDeleted.class,
                NotificationRequested.class,
                AuditEventRecorded.class);
    }
}
