package io.pallet.gitintegration.installation;

import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Named resume points for background jobs that walk GitHub, such as a listing's last {@code ETag}. */
@Repository
public class SyncCursorRepository {

    private final JdbcClient jdbc;

    SyncCursorRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Optional<String> find(String name) {
        return jdbc.sql("SELECT cursor FROM git_integration.sync_cursors WHERE name = :name")
                .param("name", name)
                .query(String.class)
                .optional();
    }

    public void save(String name, String cursor) {
        jdbc.sql("""
                        INSERT INTO git_integration.sync_cursors (name, cursor, updated_at)
                        VALUES (:name, :cursor, now())
                        ON CONFLICT (name) DO UPDATE SET cursor = EXCLUDED.cursor, updated_at = now()
                        """).param("name", name).param("cursor", cursor).update();
    }

    public void delete(String name) {
        jdbc.sql("DELETE FROM git_integration.sync_cursors WHERE name = :name")
                .param("name", name)
                .update();
    }
}
