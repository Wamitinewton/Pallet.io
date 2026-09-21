package io.pallet.orgteam.inbox;

import io.pallet.orgteam.observability.OrgTeamMetrics;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionalInbox {

    private final JdbcClient jdbc;
    private final OrgTeamMetrics metrics;

    TransactionalInbox(JdbcClient jdbc, OrgTeamMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /** @return true only for the first delivery of {@code eventId} to {@code consumer} */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(String consumer, UUID eventId) {
        int inserted = jdbc.sql("""
                        INSERT INTO org_team.processed_events (event_id, consumer)
                        VALUES (:eventId, :consumer)
                        ON CONFLICT DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("consumer", consumer)
                .update();
        if (inserted == 0) {
            metrics.inboxDuplicate(consumer);
        }
        return inserted == 1;
    }
}
