package io.pallet.gitintegration.installation;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.NotificationRequested;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Installations linked by any number of orgs, with lifecycle deliveries stored as ingestion would store them and run
 * through {@link DeliveryProcessor#processBatch()}. {@link #cleanUp()} removes every row it or its fixtures wrote.
 */
final class LifecycleScenario {

    private static final int MAX_CYCLES = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    final ReadModelFixtures fixtures;

    private final JdbcTemplate jdbc;
    private final DeliveryProcessor processor;
    private final List<UUID> deliveries = new ArrayList<>();

    LifecycleScenario(JdbcTemplate jdbc, DeliveryProcessor processor) {
        this.jdbc = jdbc;
        this.processor = processor;
        this.fixtures = new ReadModelFixtures(jdbc);
    }

    /** A notification as the outbox holds it. */
    record Notice(String orgId, String type, String audience, String dedupeKey, JsonNode variables) {

        List<String> appSlugs() {
            List<String> slugs = new ArrayList<>();
            variables.path("appSlugs").forEach(slug -> slugs.add(slug.asString()));
            return slugs;
        }
    }

    static String sha(char c) {
        return String.valueOf(c).repeat(40);
    }

    String orgLinkedTo(long installationId) {
        String orgId = fixtures.newOrg();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        return orgId;
    }

    UUID store(String fixture, Map<String, Object> overrides) {
        WebhookFixtures.Delivery delivery = WebhookFixtures.delivery(fixture);
        overrides.forEach(delivery::with);
        byte[] body = delivery.body();
        JsonNode payload = JSON.readTree(body);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO git_integration.webhook_deliveries
                    (delivery_id, event, action, installation_id, payload, status)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), 'RECEIVED')
                """,
                id,
                WebhookFixtures.eventOf(fixture),
                payload.path("action").asString(null),
                payload.path("installation").path("id").asLong(),
                new String(body, StandardCharsets.UTF_8));
        deliveries.add(id);
        return id;
    }

    /** Runs cycles until the delivery has an outcome. */
    Map<String, Object> process(UUID id) {
        for (int cycle = 0; cycle < MAX_CYCLES && "RECEIVED".equals(delivery(id).get("status")); cycle++) {
            processor.processBatch();
        }
        return delivery(id);
    }

    Map<String, Object> deliver(String fixture, Map<String, Object> overrides) {
        return process(store(fixture, overrides));
    }

    /** Sends a finished delivery through the processor again, as a crash between commit and cleanup might. */
    Map<String, Object> reprocess(UUID id) {
        jdbc.update(
                "UPDATE git_integration.webhook_deliveries SET status = 'RECEIVED', next_attempt_at = now()"
                        + " WHERE delivery_id = ?",
                id);
        return process(id);
    }

    Map<String, Object> delivery(UUID id) {
        return jdbc.queryForMap("SELECT * FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id);
    }

    Map<String, Object> installation(long installationId) {
        return jdbc.queryForMap("""
                SELECT status, unused_since, suspended_at, deleted_at, permissions::text AS permissions
                  FROM git_integration.installations WHERE installation_id = ?
                """, installationId);
    }

    int installations(long installationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.installations WHERE installation_id = ?",
                Integer.class,
                installationId);
    }

    String linkStatus(long installationId, String orgId) {
        return jdbc.queryForObject(
                "SELECT status FROM git_integration.installation_links WHERE installation_id = ? AND org_id = ?",
                String.class,
                installationId,
                orgId);
    }

    Map<String, Object> repoLink(UUID appId) {
        return jdbc.queryForMap("SELECT * FROM git_integration.repo_links WHERE app_id = ?", appId);
    }

    String slug(UUID appId) {
        return jdbc.queryForObject("SELECT slug FROM git_integration.apps WHERE app_id = ?", String.class, appId);
    }

    void head(UUID appId, String sha) {
        jdbc.update(
                "INSERT INTO git_integration.branch_heads (app_id, branch, head_sha) VALUES (?, 'main', ?)",
                appId,
                sha);
    }

    int heads(UUID appId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.branch_heads WHERE app_id = ?", Integer.class, appId);
    }

    void repository(long installationId, long repoId, String fullName) {
        jdbc.update("""
                INSERT INTO git_integration.installation_repositories
                    (installation_id, repo_id, full_name, default_branch, is_private)
                VALUES (?, ?, ?, 'main', true)
                """, installationId, repoId, fullName);
    }

    List<Map<String, Object>> repositories(long installationId) {
        return jdbc.queryForList("""
                SELECT repo_id, full_name, default_branch, is_private, archived
                  FROM git_integration.installation_repositories
                 WHERE installation_id = ? ORDER BY repo_id
                """, installationId);
    }

    List<Notice> notices(String orgId) {
        return jdbc.query(
                """
                SELECT payload::text AS payload FROM git_integration.outbox_events
                 WHERE org_id = ? AND event_type = ? ORDER BY id
                """,
                (row, i) -> {
                    JsonNode payload = JSON.readTree(row.getString("payload"));
                    return new Notice(
                            payload.path("orgId").asString(),
                            payload.path("notificationType").asString(),
                            payload.path("audience").asString(),
                            payload.path("dedupeKey").asString(),
                            payload.path("variables"));
                },
                orgId,
                NotificationRequested.TYPE);
    }

    List<JsonNode> audits(String orgId, String action) {
        return jdbc.query(
                """
                SELECT payload::text AS payload FROM git_integration.outbox_events
                 WHERE org_id = ? AND event_type = ? AND payload ->> 'action' = ? ORDER BY id
                """, (row, i) -> JSON.readTree(row.getString("payload")), orgId, AuditEventRecorded.TYPE, action);
    }

    int events(String orgId, String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.outbox_events WHERE org_id = ? AND event_type = ?",
                Integer.class,
                orgId,
                eventType);
    }

    void cleanUp() {
        deliveries.forEach(
                id -> jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id));
        deliveries.clear();
        fixtures.cleanUp();
    }
}
