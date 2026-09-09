package io.pallet.notification.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.EventIdempotencyGuard;
import io.pallet.common.observability.Monitored;
import io.pallet.notification.audience.AudienceResolver;
import io.pallet.notification.audience.Recipient;
import io.pallet.notification.channel.ChannelRouter;
import io.pallet.notification.domain.Audience;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.domain.Notification;
import io.pallet.notification.repository.NotificationRepository;
import io.pallet.notification.template.NotificationTemplate;
import io.pallet.notification.template.NotificationTemplateRegistry;
import io.pallet.notification.template.TemplateRenderer;
import io.pallet.notification.template.TemplateRenderer.RenderedNotification;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure wiring over checkpoints 3-7, in the exact order {@code ARCHITECTURE.md}'s processing
 * pipeline specifies: idempotency pre-check, resolve template, render, insert the
 * {@link Notification} once, resolve audience, fan out. The real dedupe guarantee is the
 * {@code source_event_id} unique-constraint violation caught in {@link #insertOnce}, not the
 * pre-check alone.
 *
 * <p>The shared consumer factory deserializes values to {@link JsonNode} (one topic can carry
 * more than one event type), so the record is converted to {@link NotificationRequested}
 * explicitly here rather than declared as the listener parameter type — Spring Kafka has no
 * registered converter from a JSON tree to an arbitrary record.
 */
@Component
class NotificationRequestedListener {

    private static final String BROADCAST_EMPTY_METRIC = "notifications.broadcast_empty";

    /**
     * The DB-backed guard ignores this — it's an existence check, not a TTL-backed reservation —
     * but the interface still requires a value.
     */
    private static final Duration PRE_CHECK_RETENTION = Duration.ofMinutes(10);

    private final EventIdempotencyGuard idempotencyGuard;
    private final NotificationTemplateRegistry templateRegistry;
    private final TemplateRenderer templateRenderer;
    private final AudienceResolver audienceResolver;
    private final NotificationRepository notificationRepository;
    private final ChannelRouter channelRouter;
    private final MeterRegistry meterRegistry;
    private final JsonMapper jsonMapper;

    NotificationRequestedListener(
            EventIdempotencyGuard idempotencyGuard,
            NotificationTemplateRegistry templateRegistry,
            TemplateRenderer templateRenderer,
            AudienceResolver audienceResolver,
            NotificationRepository notificationRepository,
            ChannelRouter channelRouter,
            MeterRegistry meterRegistry,
            ObjectProvider<JsonMapper> jsonMapper) {
        this.idempotencyGuard = idempotencyGuard;
        this.templateRegistry = templateRegistry;
        this.templateRenderer = templateRenderer;
        this.audienceResolver = audienceResolver;
        this.notificationRepository = notificationRepository;
        this.channelRouter = channelRouter;
        this.meterRegistry = meterRegistry;
        this.jsonMapper = jsonMapper.getIfAvailable(() -> JsonMapper.builder().build());
    }

    @KafkaListener(topics = Topics.NOTIFICATION_REQUESTED)
    @Monitored
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        NotificationRequested event = jsonMapper.treeToValue(record.value(), NotificationRequested.class);

        if (!idempotencyGuard.markProcessed(event.eventId().toString(), PRE_CHECK_RETENTION)) {
            return;
        }

        NotificationTemplate template = templateRegistry.resolve(event.notificationType());
        RenderedNotification rendered = templateRenderer.render(template, event.variables());
        Audience audience = Audience.valueOf(event.audience());
        List<Recipient> recipients = audienceResolver.resolve(event.orgId(), audience, event.recipient());

        Notification notification = insertOnce(event, audience, rendered);
        if (notification == null) {
            return;
        }

        Set<Channel> channels =
                event.channel() != null ? Set.of(Channel.valueOf(event.channel())) : template.defaultChannels();

        if (audience == Audience.ORG && recipients.isEmpty()) {
            meterRegistry.counter(BROADCAST_EMPTY_METRIC).increment();
        }

        channelRouter.fanOut(notification, channels, recipients);
    }

    private Notification insertOnce(NotificationRequested event, Audience audience, RenderedNotification rendered) {
        Notification notification = new Notification(
                event.orgId(),
                event.notificationType(),
                event.eventId(),
                event.dedupeKey(),
                audience,
                rendered.title(),
                rendered.body(),
                event.variables());
        try {
            return notificationRepository.save(notification);
        } catch (DataIntegrityViolationException e) {
            return null;
        }
    }
}
