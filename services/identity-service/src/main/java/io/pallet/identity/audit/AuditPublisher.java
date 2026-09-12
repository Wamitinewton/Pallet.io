package io.pallet.identity.audit;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds and publishes {@link AuditEventRecorded} with this service's conventions. Reused by every
 * checkpoint that records an authentication-state change (sign-up, invite-accept, login,
 * password reset, email verification).
 */
@Component
public class AuditPublisher {

    private final PlatformEventPublisher publisher;

    public AuditPublisher(PlatformEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(String orgId, String actorUserId, String action, String resource, Map<String, Object> context) {
        publisher.publish(AuditEventRecorded.of(orgId, actorUserId, action, resource, context));
    }
}
