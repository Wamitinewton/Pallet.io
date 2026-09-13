package io.pallet.identity.membership;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.events.OrgMemberRemoved;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.EventIdempotencyGuard;
import io.pallet.common.observability.Monitored;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.identity.account.IdentityUser;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.config.IdentityServiceProperties;
import jakarta.ws.rs.NotFoundException;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Disables the Keycloak account behind a removed org member. Guard check, then the Keycloak call,
 * then the local write — in that order, so a redelivery never marks a row disabled before Keycloak
 * confirms it actually happened.
 */
@Component
class OrgMemberRemovedListener {

    private static final Logger log = LoggerFactory.getLogger(OrgMemberRemovedListener.class);
    private static final String KEYCLOAK_ADMIN_POLICY = "keycloak-admin";
    private static final Duration GUARD_RETENTION = Duration.ofMinutes(10);
    private static final String EVENT_NAME = "OrgMemberRemoved";
    private static final String PROCESSED_METRIC = "identity.membership_sync.processed";
    private static final String NOOP_METRIC = "identity.membership_sync.noop";
    private static final String EVENT_TAG = "event";

    private final EventIdempotencyGuard idempotencyGuard;
    private final ExternalCall externalCall;
    private final Keycloak keycloakAdminClient;
    private final IdentityServiceProperties properties;
    private final IdentityUserRepository identityUserRepository;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;

    OrgMemberRemovedListener(
            EventIdempotencyGuard idempotencyGuard,
            ExternalCall externalCall,
            Keycloak keycloakAdminClient,
            IdentityServiceProperties properties,
            IdentityUserRepository identityUserRepository,
            JsonMapper jsonMapper,
            MeterRegistry meterRegistry) {
        this.idempotencyGuard = idempotencyGuard;
        this.externalCall = externalCall;
        this.keycloakAdminClient = keycloakAdminClient;
        this.properties = properties;
        this.identityUserRepository = identityUserRepository;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(id = "org-member-removed-listener", idIsGroup = false, topics = Topics.ORG_MEMBER_REMOVED)
    @Monitored
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        OrgMemberRemoved event = jsonMapper.treeToValue(record.value(), OrgMemberRemoved.class);

        if (!idempotencyGuard.markProcessed(event.eventId().toString(), GUARD_RETENTION)) {
            return;
        }

        try {
            externalCall.run(KEYCLOAK_ADMIN_POLICY, () -> disableIfPresent(event.userId()));
        } catch (ExternalServiceException e) {
            idempotencyGuard.release(event.eventId().toString());
            throw e;
        }

        identityUserRepository
                .findByKeycloakUserId(event.userId())
                .ifPresentOrElse(this::disableLocally, () -> logNoOp(event.orgId(), event.userId()));
    }

    /**
     * A missing Keycloak user is a deterministic outcome (already gone, or the event is stale
     * test/rollout noise) — classified here, at the call site, rather than left for
     * {@link ExternalCall}'s retry logic to treat as an infra failure worth retrying.
     */
    private void disableIfPresent(String keycloakUserId) {
        try {
            UserResource userResource = keycloakAdminClient
                    .realm(properties.keycloak().realm())
                    .users()
                    .get(keycloakUserId);
            UserRepresentation representation = userResource.toRepresentation();
            representation.setEnabled(false);
            userResource.update(representation);
        } catch (NotFoundException e) {
            log.info("Keycloak user {} already absent while handling OrgMemberRemoved", keycloakUserId);
        }
    }

    private void disableLocally(IdentityUser user) {
        user.disable();
        identityUserRepository.save(user);
        meterRegistry.counter(PROCESSED_METRIC, EVENT_TAG, EVENT_NAME).increment();
    }

    private void logNoOp(String orgId, String userId) {
        log.info("OrgMemberRemoved for org {} user {} has no local identity.users row - no-op", orgId, userId);
        meterRegistry.counter(NOOP_METRIC, EVENT_TAG, EVENT_NAME).increment();
    }
}
