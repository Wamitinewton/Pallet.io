package io.pallet.identity.membership;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.events.OrgMemberRoleChanged;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.EventIdempotencyGuard;
import io.pallet.common.observability.Monitored;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.identity.config.IdentityServiceProperties;
import jakarta.ws.rs.NotFoundException;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces a member's Keycloak realm-role assignment. This service never stores a role locally
 * (it's a token claim, read fresh from Keycloak per {@code ARCHITECTURE.md}'s domain model), so
 * there's no local write after the Keycloak call succeeds.
 */
@Component
class OrgMemberRoleChangedListener {

    private static final Logger log = LoggerFactory.getLogger(OrgMemberRoleChangedListener.class);
    private static final String KEYCLOAK_ADMIN_POLICY = "keycloak-admin";
    private static final Duration GUARD_RETENTION = Duration.ofMinutes(10);
    private static final String EVENT_NAME = "OrgMemberRoleChanged";
    private static final String PROCESSED_METRIC = "identity.membership_sync.processed";
    private static final String NOOP_METRIC = "identity.membership_sync.noop";
    private static final String EVENT_TAG = "event";

    private final EventIdempotencyGuard idempotencyGuard;
    private final ExternalCall externalCall;
    private final Keycloak keycloakAdminClient;
    private final IdentityServiceProperties properties;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;

    OrgMemberRoleChangedListener(
            EventIdempotencyGuard idempotencyGuard,
            ExternalCall externalCall,
            Keycloak keycloakAdminClient,
            IdentityServiceProperties properties,
            JsonMapper jsonMapper,
            MeterRegistry meterRegistry) {
        this.idempotencyGuard = idempotencyGuard;
        this.externalCall = externalCall;
        this.keycloakAdminClient = keycloakAdminClient;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(id = "org-member-role-changed-listener", idIsGroup = false, topics = Topics.ORG_MEMBER_ROLE_CHANGED)
    @Monitored
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        OrgMemberRoleChanged event = jsonMapper.treeToValue(record.value(), OrgMemberRoleChanged.class);

        if (!idempotencyGuard.markProcessed(event.eventId().toString(), GUARD_RETENTION)) {
            return;
        }

        boolean applied;
        try {
            applied = externalCall.call(
                    KEYCLOAK_ADMIN_POLICY,
                    () -> replaceRoleIfPresent(event.userId(), event.previousRole(), event.newRole()));
        } catch (ExternalServiceException e) {
            idempotencyGuard.release(event.eventId().toString());
            throw e;
        }

        if (applied) {
            meterRegistry.counter(PROCESSED_METRIC, EVENT_TAG, EVENT_NAME).increment();
        } else {
            log.info(
                    "OrgMemberRoleChanged for org {} user {} has no matching Keycloak account - no-op",
                    event.orgId(),
                    event.userId());
            meterRegistry.counter(NOOP_METRIC, EVENT_TAG, EVENT_NAME).increment();
        }
    }

    /**
     * A missing Keycloak user is classified here, at the call site, rather than left for
     * {@link ExternalCall}'s retry logic to treat as an infra failure.
     */
    private boolean replaceRoleIfPresent(String keycloakUserId, String previousRole, String newRole) {
        try {
            RoleScopeResource roleScope = keycloakAdminClient
                    .realm(properties.keycloak().realm())
                    .users()
                    .get(keycloakUserId)
                    .roles()
                    .realmLevel();
            roleScope.add(List.of(roleRepresentation(newRole)));
            if (!previousRole.equals(newRole)) {
                roleScope.remove(List.of(roleRepresentation(previousRole)));
            }
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    private RoleRepresentation roleRepresentation(String role) {
        return keycloakAdminClient
                .realm(properties.keycloak().realm())
                .roles()
                .get(role)
                .toRepresentation();
    }
}
