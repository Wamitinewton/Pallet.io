package io.pallet.common.outbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.assertions.OutboxSchemaAssertions;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@OutboxIntegrationTest
class OutboxSchemaAssertionsIntegrationTest {

    private static final String DRIFTED = "outbox_drifted";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void dropDrifted() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + DRIFTED + " CASCADE");
    }

    @Test
    void theReferenceSchemaMatchesItself() {
        assertThatCode(() -> OutboxSchemaAssertions.assertMatchesReference(dataSource, OutboxTestApplication.SCHEMA))
                .doesNotThrowAnyException();
    }

    @Test
    void aSchemaMissingTxIdFails() throws Exception {
        createDriftedCopy();
        jdbc.execute("ALTER TABLE " + DRIFTED + ".outbox_events DROP COLUMN tx_id CASCADE");

        assertThatThrownBy(() -> OutboxSchemaAssertions.assertMatchesReference(dataSource, DRIFTED))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("outbox_events.tx_id");
    }

    @Test
    void aSchemaMissingThePendingIndexFails() throws Exception {
        createDriftedCopy();
        jdbc.execute("DROP INDEX " + DRIFTED + ".ix_outbox_pending");

        assertThatThrownBy(() -> OutboxSchemaAssertions.assertMatchesReference(dataSource, DRIFTED))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ix_outbox_pending");
    }

    private void createDriftedCopy() throws Exception {
        String sql;
        try (InputStream in =
                getClass().getClassLoader().getResourceAsStream("META-INF/pallet/outbox/reference-schema.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String script = sql;
        jdbc.execute("CREATE SCHEMA " + DRIFTED);
        jdbc.execute((Connection connection) -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + DRIFTED);
                statement.execute(script);
                statement.execute("SET search_path TO DEFAULT");
            }
            return null;
        });
    }
}
