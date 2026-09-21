package io.pallet.notification.audience;

import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgMemberRemoved;
import io.pallet.common.events.Topics;
import io.pallet.common.observability.Monitored;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class OrgMembershipEventListener {

    private final OrgMembershipProjectionService projection;
    private final JsonMapper jsonMapper;

    OrgMembershipEventListener(OrgMembershipProjectionService projection, ObjectProvider<JsonMapper> jsonMapper) {
        this.projection = projection;
        this.jsonMapper = jsonMapper.getIfAvailable(() -> JsonMapper.builder().build());
    }

    @KafkaListener(id = "org-member-added-listener", idIsGroup = false, topics = Topics.ORG_MEMBER_ADDED)
    @Monitored
    void onMemberAdded(ConsumerRecord<String, JsonNode> record) {
        projection.apply(jsonMapper.treeToValue(record.value(), OrgMemberAdded.class));
    }

    @KafkaListener(id = "org-member-removed-listener", idIsGroup = false, topics = Topics.ORG_MEMBER_REMOVED)
    @Monitored
    void onMemberRemoved(ConsumerRecord<String, JsonNode> record) {
        projection.apply(jsonMapper.treeToValue(record.value(), OrgMemberRemoved.class));
    }

    @KafkaListener(id = "org-deleted-listener", idIsGroup = false, topics = Topics.ORG_DELETED)
    @Monitored
    void onOrgDeleted(ConsumerRecord<String, JsonNode> record) {
        projection.apply(jsonMapper.treeToValue(record.value(), OrgDeleted.class));
    }
}
