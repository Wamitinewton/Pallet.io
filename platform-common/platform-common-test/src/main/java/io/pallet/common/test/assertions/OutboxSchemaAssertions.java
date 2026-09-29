package io.pallet.common.test.assertions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Checks that a service's {@code outbox_events} and {@code processed_events} match {@code platform-common-outbox}'s
 * {@code reference-schema.sql}: the same columns, types, nullability and defaults, and at least the reference's
 * indexes and check constraints. The reference is built in a throwaway schema inside a transaction that is rolled
 * back.
 */
public final class OutboxSchemaAssertions {

    static final String REFERENCE_SCHEMA = "META-INF/pallet/outbox/reference-schema.sql";

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final List<String> TABLES = List.of("outbox_events", "processed_events");

    private OutboxSchemaAssertions() {}

    public static void assertMatchesReference(DataSource dataSource, String schema) {
        requireIdentifier(schema);
        String reference = "outbox_reference_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA " + reference);
                    statement.execute("SET LOCAL search_path TO " + reference);
                    statement.execute(referenceSql());
                }
                List<String> differences = compare(describe(connection, reference), describe(connection, schema));
                assertThat(differences)
                        .as("%s outbox tables differ from %s", schema, REFERENCE_SCHEMA)
                        .isEmpty();
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not compare " + schema + " with the outbox reference schema", e);
        }
    }

    private static List<String> compare(Shape expected, Shape actual) {
        List<String> differences = new ArrayList<>();
        expected.columns().forEach((column, definition) -> {
            String found = actual.columns().get(column);
            if (!definition.equals(found)) {
                differences.add("column " + column + ": expected [" + definition + "] but was [" + found + "]");
            }
        });
        actual.columns().keySet().stream()
                .filter(column -> !expected.columns().containsKey(column))
                .forEach(column -> differences.add("column " + column + " is not in the reference schema"));
        expected.indexes().forEach((index, definition) -> {
            if (!definition.equals(actual.indexes().get(index))) {
                differences.add("index " + index + ": expected [" + definition + "] but was ["
                        + actual.indexes().get(index) + "]");
            }
        });
        expected.checks().forEach((check, definition) -> {
            if (!definition.equals(actual.checks().get(check))) {
                differences.add("check " + check + ": expected [" + definition + "] but was ["
                        + actual.checks().get(check) + "]");
            }
        });
        return differences;
    }

    private static Shape describe(Connection connection, String schema) throws SQLException {
        Map<String, String> columns = new TreeMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT table_name, column_name, data_type, character_maximum_length, is_nullable,
                       column_default, is_identity
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ANY (?)
                """)) {
            query.setString(1, schema);
            query.setArray(2, connection.createArrayOf("text", TABLES.toArray()));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    columns.put(
                            rows.getString("table_name") + "." + rows.getString("column_name"),
                            String.join(
                                    " ",
                                    rows.getString("data_type"),
                                    Objects.toString(rows.getObject("character_maximum_length"), "-"),
                                    "nullable=" + rows.getString("is_nullable"),
                                    "identity=" + rows.getString("is_identity"),
                                    "default=" + rows.getString("column_default")));
                }
            }
        }
        Map<String, String> indexes = new TreeMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ANY (?)
                """)) {
            query.setString(1, schema);
            query.setArray(2, connection.createArrayOf("text", TABLES.toArray()));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    indexes.put(
                            rows.getString("indexname"),
                            rows.getString("indexdef").replace(schema + ".", ""));
                }
            }
        }
        Map<String, String> checks = new TreeMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT c.conname, pg_get_constraintdef(c.oid) AS definition
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = ? AND t.relname = ANY (?) AND c.contype = 'c'
                """)) {
            query.setString(1, schema);
            query.setArray(2, connection.createArrayOf("text", TABLES.toArray()));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    checks.put(rows.getString("conname"), rows.getString("definition"));
                }
            }
        }
        return new Shape(columns, indexes, checks);
    }

    private static String referenceSql() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream in = loader.getResourceAsStream(REFERENCE_SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException(
                        REFERENCE_SCHEMA + " is not on the classpath: add platform-common-outbox as a dependency");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + REFERENCE_SCHEMA, e);
        }
    }

    private static void requireIdentifier(String schema) {
        if (schema == null || !IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Not a lowercase Postgres identifier: " + schema);
        }
    }

    private record Shape(Map<String, String> columns, Map<String, String> indexes, Map<String, String> checks) {}
}
