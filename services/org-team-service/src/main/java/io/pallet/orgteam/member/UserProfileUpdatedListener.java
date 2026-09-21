package io.pallet.orgteam.member;

import io.pallet.common.events.Topics;
import io.pallet.common.events.UserProfileUpdated;
import io.pallet.orgteam.inbox.EventPayloads;
import io.pallet.orgteam.inbox.MalformedEventException;
import io.pallet.orgteam.inbox.TransactionalInbox;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class UserProfileUpdatedListener {

    static final String CONSUMER = MetricsCatalog.LISTENER_USER_PROFILE_UPDATED;

    private final JsonMapper jsonMapper;
    private final TransactionalInbox inbox;
    private final OrgTeamMetrics metrics;
    private final ProfileProjectionService projection;

    UserProfileUpdatedListener(
            JsonMapper jsonMapper,
            TransactionalInbox inbox,
            OrgTeamMetrics metrics,
            ProfileProjectionService projection) {
        this.jsonMapper = jsonMapper;
        this.inbox = inbox;
        this.metrics = metrics;
        this.projection = projection;
    }

    @KafkaListener(id = "user-profile-updated-listener", idIsGroup = false, topics = Topics.USER_PROFILE_UPDATED)
    @Transactional
    void onMessage(ConsumerRecord<String, JsonNode> record) {
        metrics.countingFailures(CONSUMER, () -> handle(record));
    }

    private void handle(ConsumerRecord<String, JsonNode> record) {
        UserProfileUpdated event = EventPayloads.read(jsonMapper, record.value(), UserProfileUpdated.class);
        if (event.eventId() == null) {
            throw new MalformedEventException("UserProfileUpdated.eventId is required");
        }
        if (!inbox.firstDelivery(CONSUMER, event.eventId())) {
            return;
        }
        projection.apply(event);
    }
}
