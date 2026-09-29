package io.pallet.orgteam.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.RepositoryTest;
import io.pallet.common.test.assertions.OutboxSchemaAssertions;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@RepositoryTest
class OutboxSchemaIntegrationTest {

    private static final String VERSION_BEFORE_RECORD_KEY = "4";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void theOutboxTablesMatchTheModulesReferenceSchema() {
        OutboxSchemaAssertions.assertMatchesReference(dataSource, "org_team");
    }

    @Test
    void aTombstoneRowWithAPayloadIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO org_team.outbox_events (event_id, org_id, event_type, payload, record_key, tombstone)
                        VALUES (?, 'org-a', 'org.membership.changed', '{}'::jsonb, 'org-a:user-1', true)
                        """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_events_tombstone");
    }

    @Test
    void rowsWrittenBeforeTheRecordKeyMigrationKeepTheirOrgKeyAndStayPending() {
        String database = "outbox_record_key_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate server = new JdbcTemplate(dataSource(postgres.getJdbcUrl()));
        server.execute("CREATE DATABASE " + database);
        try {
            String url =
                    "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
            JdbcTemplate scratch = new JdbcTemplate(dataSource(url));
            UUID pending = UUID.randomUUID();

            flyway(url, MigrationVersion.fromVersion(VERSION_BEFORE_RECORD_KEY)).migrate();
            scratch.update("""
                    INSERT INTO org_team.outbox_events (event_id, org_id, event_type, payload)
                    VALUES (?, 'org-a', 'org.member.added', '{"orgId":"org-a"}'::jsonb)
                    """, pending);

            flyway(url, MigrationVersion.LATEST).migrate();

            assertThat(scratch.queryForMap(
                            "SELECT status, record_key, tombstone FROM org_team.outbox_events WHERE event_id = ?",
                            pending))
                    .containsExactlyInAnyOrderEntriesOf(
                            mapOf("status", "PENDING", "record_key", null, "tombstone", false));
            OutboxSchemaAssertions.assertMatchesReference(dataSource(url), "org_team");
        } finally {
            server.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private Flyway flyway(String url, MigrationVersion target) {
        return Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .schemas("org_team")
                .defaultSchema("org_team")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private SimpleDriverDataSource dataSource(String url) {
        return new SimpleDriverDataSource(
                new org.postgresql.Driver(), url, postgres.getUsername(), postgres.getPassword());
    }
}
