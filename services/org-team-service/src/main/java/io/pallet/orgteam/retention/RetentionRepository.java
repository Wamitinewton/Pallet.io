package io.pallet.orgteam.retention;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RetentionRepository {

    private final JdbcClient jdbc;

    RetentionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    int deletePublishedOutbox(double windowSeconds, int batchSize) {
        return jdbc.sql("""
                        DELETE FROM org_team.outbox_events
                        WHERE id IN (
                            SELECT id FROM org_team.outbox_events
                            WHERE status = 'PUBLISHED'
                              AND published_at < now() - make_interval(secs => :windowSeconds)
                            ORDER BY published_at
                            LIMIT :batchSize)
                        """)
                .param("windowSeconds", windowSeconds)
                .param("batchSize", batchSize)
                .update();
    }

    int deleteProcessedEvents(double windowSeconds, int batchSize) {
        return jdbc.sql("""
                        DELETE FROM org_team.processed_events
                        WHERE ctid IN (
                            SELECT ctid FROM org_team.processed_events
                            WHERE processed_at < now() - make_interval(secs => :windowSeconds)
                            ORDER BY processed_at
                            LIMIT :batchSize)
                        """)
                .param("windowSeconds", windowSeconds)
                .param("batchSize", batchSize)
                .update();
    }
}
