package io.pallet.gitintegration.delivery;

import io.pallet.gitintegration.support.WebhookFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Delivery rows written straight into {@code webhook_deliveries}, as ingestion would, and removed after each test. */
final class DeliveryRows {

    private final JdbcTemplate jdbc;
    private final List<UUID> inserted = new ArrayList<>();

    DeliveryRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(String fixture) {
        return insert(fixture, Map.of(), null);
    }

    UUID insert(String fixture, Map<String, Object> overrides, String traceparent) {
        WebhookFixtures.Delivery delivery = WebhookFixtures.delivery(fixture);
        overrides.forEach(delivery::with);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO git_integration.webhook_deliveries
                    (delivery_id, event, installation_id, payload, status, traceparent)
                VALUES (?, ?, 41000001, CAST(? AS jsonb), 'RECEIVED', ?)
                """,
                id,
                WebhookFixtures.eventOf(fixture),
                new String(delivery.body(), StandardCharsets.UTF_8),
                traceparent);
        inserted.add(id);
        return id;
    }

    List<UUID> insertMany(String fixture, int count) {
        String payload = new String(WebhookFixtures.load(fixture), StandardCharsets.UTF_8);
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        jdbc.batchUpdate("""
                INSERT INTO git_integration.webhook_deliveries (delivery_id, event, installation_id, payload, status)
                VALUES (?, ?, 41000001, CAST(? AS jsonb), 'RECEIVED')
                """, ids, 100, (statement, id) -> {
            statement.setObject(1, id);
            statement.setString(2, WebhookFixtures.eventOf(fixture));
            statement.setString(3, payload);
        });
        inserted.addAll(ids);
        return ids;
    }

    Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id);
    }

    String status(UUID id) {
        return (String) row(id).get("status");
    }

    int attempts(UUID id) {
        return (Integer) row(id).get("attempts");
    }

    /** Seconds from the database's {@code now()} until the delivery is due. */
    double secondsUntilDue(UUID id) {
        return jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM next_attempt_at - now()) FROM git_integration.webhook_deliveries"
                        + " WHERE delivery_id = ?",
                Double.class,
                id);
    }

    void makeDue(UUID id) {
        jdbc.update("UPDATE git_integration.webhook_deliveries SET next_attempt_at = now() WHERE delivery_id = ?", id);
    }

    void removeAll() {
        inserted.forEach(id -> jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id));
        inserted.clear();
    }
}
