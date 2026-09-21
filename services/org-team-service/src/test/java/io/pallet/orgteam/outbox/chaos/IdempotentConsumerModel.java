package io.pallet.orgteam.outbox.chaos;

import io.pallet.orgteam.inbox.TransactionalInbox;
import io.pallet.orgteam.outbox.TopicProbe.Received;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stands in for a downstream consumer using the same inbox guard the real listeners use, so a test
 * can prove that at-least-once delivery still yields exactly-once effects.
 */
final class IdempotentConsumerModel {

    private final String consumerName = "chaos-consumer-" + UUID.randomUUID();
    private final TransactionalInbox inbox;
    private final TransactionTemplate transaction;

    IdempotentConsumerModel(TransactionalInbox inbox, TransactionTemplate transaction) {
        this.inbox = inbox;
        this.transaction = transaction;
    }

    /** @return how many deliveries were the first for their event id, i.e. how many effects were applied */
    int apply(List<Received> deliveries) {
        int effects = 0;
        for (Received delivery : deliveries) {
            if (Boolean.TRUE.equals(
                    transaction.execute(status -> inbox.firstDelivery(consumerName, delivery.eventId())))) {
                effects++;
            }
        }
        return effects;
    }

    void cleanUp(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM org_team.processed_events WHERE consumer = ?", consumerName);
    }
}
