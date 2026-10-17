package io.pallet.buildqueue.projection;

import io.pallet.buildqueue.cancel.QueueTeardown;
import io.pallet.common.events.AppCreated;
import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.EventPayloads;
import io.pallet.common.inbox.TransactionalInbox;
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
 * Maintains the {@code apps} read model. An existing row is never moved back to {@code ACTIVE} or to another org, and
 * the app's org is recorded on first sight. A deletion also cancels the app's waiting builds.
 */
@Component
class AppEventListener {

    static final String CREATED_CONSUMER = "app-created";
    static final String DELETED_CONSUMER = "app-deleted";

    private static final Logger log = LoggerFactory.getLogger(AppEventListener.class);

    private final JsonMapper jsonMapper;
    private final TransactionalInbox inbox;
    private final AppProjectionRepository apps;
    private final OrgProjectionRepository orgs;
    private final QueueTeardown teardown;

    AppEventListener(
            JsonMapper jsonMapper,
            TransactionalInbox inbox,
            AppProjectionRepository apps,
            OrgProjectionRepository orgs,
            QueueTeardown teardown) {
        this.jsonMapper = jsonMapper;
        this.inbox = inbox;
        this.apps = apps;
        this.orgs = orgs;
        this.teardown = teardown;
    }

    @KafkaListener(id = "app-created-listener", idIsGroup = false, topics = Topics.APP_CREATED)
    @Transactional
    void onAppCreated(ConsumerRecord<String, JsonNode> record) {
        AppCreated event = EventPayloads.read(jsonMapper, record.value(), AppCreated.class);
        AppRef app = AppRef.of("AppCreated", event.eventId(), event.orgId(), event.appId(), event.slug());
        if (!inbox.firstDelivery(CREATED_CONSUMER, event.eventId())) {
            return;
        }
        if (apps.insertActiveIfAbsent(app.orgId(), app.appId(), app.slug()) == 0) {
            requireSameOrg("AppCreated", app);
        }
        orgs.ensureExists(app.orgId());
        log.info(
                "App projected orgId={} appId={} eventId={} type={}",
                app.orgId(),
                app.appId(),
                event.eventId(),
                event.eventType());
    }

    @KafkaListener(id = "app-deleted-listener", idIsGroup = false, topics = Topics.APP_DELETED)
    @Transactional
    void onAppDeleted(ConsumerRecord<String, JsonNode> record) {
        AppDeleted event = EventPayloads.read(jsonMapper, record.value(), AppDeleted.class);
        AppRef app = AppRef.of("AppDeleted", event.eventId(), event.orgId(), event.appId(), event.slug());
        if (!inbox.firstDelivery(DELETED_CONSUMER, event.eventId())) {
            return;
        }
        orgs.ensureExists(app.orgId());
        // Before the tombstone: intake locks the org before reading the app, so a push either commits its build first,
        // where the teardown cancels it, or waits and finds the app deleted.
        orgs.lockByOrgId(app.orgId()).orElseThrow(() -> new IllegalStateException("Org row missing after insert"));
        if (apps.upsertDeleted(app.orgId(), app.appId(), app.slug()) == 0) {
            throw new AppOrgMismatchException("AppDeleted");
        }
        int cancelled = teardown.cancelQueuedForApp(app.orgId(), app.appId());
        log.info(
                "App projected orgId={} appId={} eventId={} type={} buildsCancelled={}",
                app.orgId(),
                app.appId(),
                event.eventId(),
                event.eventType(),
                cancelled);
    }

    private void requireSameOrg(String event, AppRef app) {
        apps.findOwningOrgId(app.appId())
                .filter(owner -> !owner.equals(app.orgId()))
                .ifPresent(owner -> {
                    throw new AppOrgMismatchException(event);
                });
    }

    private record AppRef(String orgId, String appId, String slug) {

        static AppRef of(String event, UUID eventId, String orgId, String appId, String slug) {
            EventFields.requireEventId(event, eventId);
            return new AppRef(
                    EventFields.requireId(event, "orgId", orgId),
                    EventFields.requireId(event, "appId", appId),
                    EventFields.requireSlug(event, slug));
        }
    }
}
