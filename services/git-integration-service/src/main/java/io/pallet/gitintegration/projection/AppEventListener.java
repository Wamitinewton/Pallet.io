package io.pallet.gitintegration.projection;

import io.pallet.common.events.AppCreated;
import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.EventPayloads;
import io.pallet.common.inbox.MalformedEventException;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.gitintegration.installation.ConnectionTeardown;
import io.pallet.gitintegration.installation.TeardownResult;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maintains the {@code apps} read model. An existing row is never moved back to {@code ACTIVE} or to another org. A
 * deleted app's repo link is disconnected in the same transaction, so its pushes stop as the deletion commits.
 */
@Component
class AppEventListener {

    static final String CREATED_CONSUMER = "app-created-projection";
    static final String DELETED_CONSUMER = "app-deleted-projection";

    static final int MAX_ORG_ID_LENGTH = 64;
    static final int MAX_SLUG_LENGTH = 63;

    private static final Logger log = LoggerFactory.getLogger(AppEventListener.class);

    private final JsonMapper jsonMapper;
    private final TransactionalInbox inbox;
    private final AppProjectionRepository apps;
    private final ConnectionTeardown teardown;
    private final ProjectionMetrics metrics;

    AppEventListener(
            JsonMapper jsonMapper,
            TransactionalInbox inbox,
            AppProjectionRepository apps,
            ConnectionTeardown teardown,
            ProjectionMetrics metrics) {
        this.jsonMapper = jsonMapper;
        this.inbox = inbox;
        this.apps = apps;
        this.teardown = teardown;
        this.metrics = metrics;
    }

    @KafkaListener(id = "app-created-listener", idIsGroup = false, topics = Topics.APP_CREATED)
    @Transactional
    void onAppCreated(ConsumerRecord<String, JsonNode> record) {
        metrics.countingFailures(CREATED_CONSUMER, () -> created(record));
    }

    @KafkaListener(id = "app-deleted-listener", idIsGroup = false, topics = Topics.APP_DELETED)
    @Transactional
    void onAppDeleted(ConsumerRecord<String, JsonNode> record) {
        metrics.countingFailures(DELETED_CONSUMER, () -> deleted(record));
    }

    private void created(ConsumerRecord<String, JsonNode> record) {
        AppCreated event = EventPayloads.read(jsonMapper, record.value(), AppCreated.class);
        UUID appId = validate("AppCreated", event.eventId(), event.orgId(), event.appId(), event.slug());
        if (!inbox.firstDelivery(CREATED_CONSUMER, event.eventId())) {
            return;
        }
        if (apps.insertActiveIfAbsent(event.orgId(), appId, event.slug().strip()) == 0) {
            requireSameOrg("AppCreated", event.orgId(), appId);
        }
        log.info("App projected orgId={} eventId={} type={}", event.orgId(), event.eventId(), event.eventType());
    }

    private void deleted(ConsumerRecord<String, JsonNode> record) {
        AppDeleted event = EventPayloads.read(jsonMapper, record.value(), AppDeleted.class);
        UUID appId = validate("AppDeleted", event.eventId(), event.orgId(), event.appId(), event.slug());
        if (!inbox.firstDelivery(DELETED_CONSUMER, event.eventId())) {
            return;
        }
        if (apps.upsertDeleted(event.orgId(), appId, event.slug().strip()) == 0) {
            requireSameOrg("AppDeleted", event.orgId(), appId);
        }
        TeardownResult result = teardown.appDeleted(event.orgId(), appId);
        log.info(
                "App projected orgId={} eventId={} type={} repoLinksDisconnected={}",
                event.orgId(),
                event.eventId(),
                event.eventType(),
                result.disconnected().size());
    }

    private void requireSameOrg(String event, String orgId, UUID appId) {
        apps.findOwningOrgId(appId).filter(owner -> !owner.equals(orgId)).ifPresent(owner -> {
            throw new MalformedEventException(event + ".appId already belongs to another org");
        });
    }

    private static UUID validate(String event, UUID eventId, String orgId, String appId, String slug) {
        if (eventId == null) {
            throw new MalformedEventException(event + ".eventId is required");
        }
        EventPayloads.requireText(event, "orgId", orgId, MAX_ORG_ID_LENGTH);
        EventPayloads.requireText(event, "slug", slug, MAX_SLUG_LENGTH);
        EventPayloads.requireText(event, "appId", appId, MAX_ORG_ID_LENGTH);
        try {
            UUID parsed = UUID.fromString(appId);
            if (!parsed.toString().equals(appId)) {
                throw new MalformedEventException(event + ".appId is not a canonical UUID");
            }
            return parsed;
        } catch (IllegalArgumentException notAUuid) {
            throw new MalformedEventException(event + ".appId is not a UUID", notAUuid);
        }
    }
}
