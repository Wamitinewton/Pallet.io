package io.pallet.identity.membership;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.events.OrgInviteRejected;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.EventIdempotencyGuard;
import io.pallet.common.observability.Monitored;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.common.security.RevokedSessionRegistry;
import io.pallet.identity.account.IdentityUser;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.audit.AuditPublisher;
import io.pallet.identity.config.IdentityServiceProperties;
import jakarta.ws.rs.NotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Disables the account created for an invite that {@code org-team-service} refused to honour. The
 * event names a Keycloak user id, so the user's {@code org_id} attribute is checked against the
 * event's {@code orgId} before anything changes: a malformed or mistargeted event can never
 * disable an account belonging to another organization.
 */
@Component
class OrgInviteRejectedListener {

    private static final Logger log = LoggerFactory.getLogger(OrgInviteRejectedListener.class);
    private static final String KEYCLOAK_ADMIN_POLICY = "keycloak-admin";
    private static final Duration GUARD_RETENTION = Duration.ofMinutes(10);
    private static final String EVENT_NAME = "OrgInviteRejected";
    private static final String PROCESSED_METRIC = "identity.membership_sync.processed";
    private static final String NOOP_METRIC = "identity.membership_sync.noop";
    private static final String EVENT_TAG = "event";
    private static final String ORG_ID_ATTRIBUTE = "org_id";
    private static final String AUDIT_ACTION = "invite-rejected-account-disabled";
    private static final String AUDIT_RESOURCE = "identity-user";

    private enum Outcome {
        DISABLED,
        NOT_APPLICABLE
    }

    private final EventIdempotencyGuard idempotencyGuard;
    private final ExternalCall externalCall;
    private final Keycloak keycloakAdminClient;
    private final IdentityServiceProperties properties;
    private final IdentityUserRepository identityUserRepository;
    private final RevokedSessionRegistry revokedSessionRegistry;
    private final AuditPublisher auditPublisher;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;

    OrgInviteRejectedListener(
            EventIdempotencyGuard idempotencyGuard,
            ExternalCall externalCall,
            Keycloak keycloakAdminClient,
            IdentityServiceProperties properties,
            IdentityUserRepository identityUserRepository,
            RevokedSessionRegistry revokedSessionRegistry,
            AuditPublisher auditPublisher,
            JsonMapper jsonMapper,
            MeterRegistry meterRegistry) {
        this.idempotencyGuard = idempotencyGuard;
        this.externalCall = externalCall;
        this.keycloakAdminClient = keycloakAdminClient;
        this.properties = properties;
        this.identityUserRepository = identityUserRepository;
        this.revokedSessionRegistry = revokedSessionRegistry;
        this.auditPublisher = auditPublisher;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(id = "org-invite-rejected-listener", idIsGroup = false, topics = Topics.ORG_INVITE_REJECTED)
    @Monitored
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        OrgInviteRejected event = jsonMapper.treeToValue(record.value(), OrgInviteRejected.class);

        if (!idempotencyGuard.markProcessed(event.eventId().toString(), GUARD_RETENTION)) {
            return;
        }

        Outcome outcome;
        try {
            outcome = externalCall.call(KEYCLOAK_ADMIN_POLICY, () -> disableIfOwnedByOrg(event));
        } catch (ExternalServiceException e) {
            idempotencyGuard.release(event.eventId().toString());
            throw e;
        }

        if (outcome == Outcome.NOT_APPLICABLE) {
            logNoOp(event);
            return;
        }

        identityUserRepository
                .findByKeycloakUserId(event.userId())
                .filter(user -> event.orgId().equals(user.getOrgId()))
                .ifPresent(this::disableLocally);
        auditPublisher.publish(
                event.orgId(),
                event.userId(),
                AUDIT_ACTION,
                AUDIT_RESOURCE,
                Map.of("inviteId", event.inviteId(), "reason", event.reason()));
        meterRegistry.counter(PROCESSED_METRIC, EVENT_TAG, EVENT_NAME).increment();
    }

    private Outcome disableIfOwnedByOrg(OrgInviteRejected event) {
        UserResource userResource;
        UserRepresentation representation;
        try {
            userResource = keycloakAdminClient
                    .realm(properties.keycloak().realm())
                    .users()
                    .get(event.userId());
            representation = userResource.toRepresentation();
        } catch (NotFoundException e) {
            return Outcome.NOT_APPLICABLE;
        }

        if (!event.orgId().equals(representation.firstAttribute(ORG_ID_ATTRIBUTE))) {
            return Outcome.NOT_APPLICABLE;
        }

        representation.setEnabled(false);
        userResource.update(representation);
        revokeSessions(userResource);
        return Outcome.DISABLED;
    }

    private void revokeSessions(UserResource userResource) {
        List<UserSessionRepresentation> sessions = userResource.getUserSessions();
        if (sessions.isEmpty()) {
            return;
        }
        Duration retention = properties.sessionRevocation().retention();
        sessions.forEach(session -> revokedSessionRegistry.revoke(session.getId(), retention));
        userResource.logout();
    }

    private void disableLocally(IdentityUser user) {
        user.disable();
        identityUserRepository.save(user);
    }

    private void logNoOp(OrgInviteRejected event) {
        log.info(
                "OrgInviteRejected for invite {} in org {} matched no Keycloak user of that org - no-op",
                event.inviteId(),
                event.orgId());
        meterRegistry.counter(NOOP_METRIC, EVENT_TAG, EVENT_NAME).increment();
    }
}
