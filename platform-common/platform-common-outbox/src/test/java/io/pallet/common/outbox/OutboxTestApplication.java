package io.pallet.common.outbox;

import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.EventPayloads;
import io.pallet.common.inbox.TransactionalInbox;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A minimal service on the {@code outbox_test} schema, standing in for any service that adopts the module. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class OutboxTestApplication {

    public static final String SCHEMA = "outbox_test";

    @Bean
    OutboxEventTypes outboxEventTypes() {
        return OutboxEventTypes.of(OrgMemberAdded.class, NotificationRequested.class, OrgMembershipChanged.class);
    }

    @Bean
    ProvisioningListener provisioningListener(
            TransactionTemplate transaction, TransactionalInbox inbox, OutboxWriter writer, JsonMapper jsonMapper) {
        return new ProvisioningListener(transaction, inbox, writer, jsonMapper);
    }

    /** Consumes one event and appends one, the shape of every inbox-then-outbox listener in a service. */
    public static final class ProvisioningListener {

        public static final String CONSUMER = "org-provisioned";

        private final TransactionTemplate transaction;
        private final TransactionalInbox inbox;
        private final OutboxWriter writer;
        private final JsonMapper jsonMapper;
        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();

        ProvisioningListener(
                TransactionTemplate transaction, TransactionalInbox inbox, OutboxWriter writer, JsonMapper jsonMapper) {
            this.transaction = transaction;
            this.inbox = inbox;
            this.writer = writer;
            this.jsonMapper = jsonMapper;
        }

        @KafkaListener(topics = Topics.ORG_PROVISIONED, groupId = "outbox-test-provisioning")
        void onProvisioned(ConsumerRecord<String, JsonNode> record) {
            OrgProvisioned event = EventPayloads.read(jsonMapper, record.value(), OrgProvisioned.class);
            try {
                transaction.executeWithoutResult(status -> {
                    if (inbox.firstDelivery(CONSUMER, event.eventId())) {
                        writer.append(OrgMemberAdded.of(event.orgId(), event.ownerUserId(), event.ownerEmail()));
                    }
                });
                processed.incrementAndGet();
            } catch (RuntimeException failure) {
                failed.incrementAndGet();
                throw failure;
            }
        }

        public int processed() {
            return processed.get();
        }

        public int failed() {
            return failed.get();
        }
    }
}
