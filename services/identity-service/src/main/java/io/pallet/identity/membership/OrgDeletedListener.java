package io.pallet.identity.membership;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.EventIdempotencyGuard;
import io.pallet.common.observability.Monitored;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.identity.account.IdentityUser;
import io.pallet.identity.account.IdentityUserRepository;
import io.pallet.identity.config.IdentityServiceProperties;
import jakarta.ws.rs.NotFoundException;
import java.time.Duration;
import java.util.List;
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
 * Disables every account under a deleted organization. The {@code OrgBootstrapRecord} itself is
 * deliberately left untouched — it's a write-once snapshot with no update methods by design (see
 * {@code OrgBootstrapRecord}'s own Javadoc); "every user under it disabled" is this service's
 * complete signal that the org is gone, so no retirement flag is added here.
 */
@Component
class OrgDeletedListener {

    private static final Logger log = LoggerFactory.getLogger(OrgDeletedListener.class);
    private static final String KEYCLOAK_ADMIN_POLICY = "keycloak-admin";
    private static final Duration GUARD_RETENTION = Duration.ofMinutes(10);
    private static final String EVENT_NAME = "OrgDeleted";
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

    OrgDeletedListener(
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

    @KafkaListener(id = "org-deleted-listener", idIsGroup = false, topics = Topics.ORG_DELETED)
    @Monitored
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        OrgDeleted event = jsonMapper.treeToValue(record.value(), OrgDeleted.class);

        if (!idempotencyGuard.markProcessed(event.eventId().toString(), GUARD_RETENTION)) {
            return;
        }

        List<IdentityUser> users = identityUserRepository.findByOrgId(event.orgId());
        if (users.isEmpty()) {
            log.info("OrgDeleted for org {} has no local identity.users rows - no-op", event.orgId());
            meterRegistry.counter(NOOP_METRIC, EVENT_TAG, EVENT_NAME).increment();
            return;
        }

        try {
            for (IdentityUser user : users) {
                externalCall.run(KEYCLOAK_ADMIN_POLICY, () -> disableIfPresent(user.getKeycloakUserId()));
            }
        } catch (ExternalServiceException e) {
            idempotencyGuard.release(event.eventId().toString());
            throw e;
        }

        users.forEach(IdentityUser::disable);
        identityUserRepository.saveAll(users);
        meterRegistry.counter(PROCESSED_METRIC, EVENT_TAG, EVENT_NAME).increment();
    }

    /**
     * A missing Keycloak user is classified here, at the call site, rather than left for
     * {@link ExternalCall}'s retry logic to treat as an infra failure.
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
            log.info("Keycloak user {} already absent while handling OrgDeleted", keycloakUserId);
        }
    }
}
