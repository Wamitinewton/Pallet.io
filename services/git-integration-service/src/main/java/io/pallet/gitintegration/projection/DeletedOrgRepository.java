package io.pallet.gitintegration.projection;

import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DeletedOrgRepository {

    private final JdbcClient jdbc;

    DeletedOrgRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Keeps the first recorded deletion if the org is already known to be deleted. */
    public void record(String orgId, Instant deletedAt) {
        jdbc.sql("""
                        INSERT INTO git_integration.deleted_orgs (org_id, deleted_at)
                        VALUES (:orgId, :deletedAt)
                        ON CONFLICT (org_id) DO NOTHING
                        """)
                .param("orgId", orgId)
                .param("deletedAt", Timestamp.from(deletedAt))
                .update();
    }
}
