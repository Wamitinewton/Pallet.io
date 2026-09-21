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
import io.pallet.common.events.PlatformEvent;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class EventTypeRegistry {

    private static final Map<String, Class<? extends PlatformEvent>> TYPES = Map.ofEntries(
            Map.entry(OrgMemberAdded.TYPE, OrgMemberAdded.class),
            Map.entry(OrgMemberRemoved.TYPE, OrgMemberRemoved.class),
            Map.entry(OrgMemberRoleChanged.TYPE, OrgMemberRoleChanged.class),
            Map.entry(OrgDeleted.TYPE, OrgDeleted.class),
            Map.entry(OrgInviteRejected.TYPE, OrgInviteRejected.class),
            Map.entry(AppCreated.TYPE, AppCreated.class),
            Map.entry(AppDeleted.TYPE, AppDeleted.class),
            Map.entry(NotificationRequested.TYPE, NotificationRequested.class),
            Map.entry(AuditEventRecorded.TYPE, AuditEventRecorded.class));

    Class<? extends PlatformEvent> classFor(String eventType) {
        Class<? extends PlatformEvent> eventClass = TYPES.get(eventType);
        if (eventClass == null) {
            throw new UnknownEventTypeException(eventType);
        }
        return eventClass;
    }

    Set<Class<? extends PlatformEvent>> publishedTypes() {
        return Set.copyOf(TYPES.values());
    }
}
