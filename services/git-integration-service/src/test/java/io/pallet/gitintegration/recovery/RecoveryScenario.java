package io.pallet.gitintegration.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.GitPushReceived;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Installations with one repository each, linked by apps in any number of orgs, and the recovery jobs' cursors reset.
 * {@link #cleanUp()} removes every row it made.
 */
final class RecoveryScenario {

    private static final int MAX_CYCLES = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    final ReadModelFixtures fixtures;

    private final JdbcTemplate jdbc;
    private final MockMvc mvc;
    private final DeliveryProcessor processor;
    private final List<UUID> deliveries = new ArrayList<>();

    RecoveryScenario(JdbcTemplate jdbc, MockMvc mvc, DeliveryProcessor processor) {
        this.jdbc = jdbc;
        this.mvc = mvc;
        this.processor = processor;
        this.fixtures = new ReadModelFixtures(jdbc);
        resetCursors();
    }

    static String sha(char c) {
        return String.valueOf(c).repeat(40);
    }

    static long newRepoId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    /** An org that has linked {@code installationId}. */
    String orgOn(long installationId) {
        String orgId = fixtures.newOrg();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        return orgId;
    }

    /** An active app auto-deploying {@code main}, its head at {@code head} unless that is null. */
    UUID linkApp(String orgId, long installationId, long repoId, String head) {
        UUID appId = fixtures.newRepoLink(orgId, installationId, repoId);
        if (head != null) {
            head(appId, head, null);
        }
        return appId;
    }

    void head(UUID appId, String sha, String etag) {
        jdbc.update(
                "INSERT INTO git_integration.branch_heads (app_id, branch, head_sha, etag) VALUES (?, 'main', ?, ?)",
                appId,
                sha,
                etag);
    }

    Optional<String> head(UUID appId) {
        return headColumn(appId, "head_sha");
    }

    Optional<String> etag(UUID appId) {
        return headColumn(appId, "etag");
    }

    /** Every {@code GitPushReceived} in the outbox for the org, in the order it was appended. */
    List<JsonNode> published(String orgId) {
        return jdbc.query("""
                SELECT payload::text AS payload FROM git_integration.outbox_events
                 WHERE org_id = ? AND event_type = ? ORDER BY id
                """, (row, i) -> JSON.readTree(row.getString("payload")), orgId, GitPushReceived.TYPE);
    }

    WebhookFixtures.Delivery push(long installationId, long repoId, String before, String after) {
        return WebhookFixtures.delivery("push-main.json")
                .with("/installation/id", installationId)
                .with("/repository/id", repoId)
                .with("/before", before)
                .with("/after", after)
                .with("/head_commit/id", after);
    }

    MockHttpServletResponse post(WebhookFixtures.Delivery delivery) {
        MockHttpServletResponse response = delivery.post(mvc);
        deliveries.add(UUID.fromString(delivery.deliveryId()));
        return response;
    }

    /** Posts and runs processor cycles until the delivery has been through one round. */
    Map<String, Object> postAndProcess(WebhookFixtures.Delivery delivery) {
        assertThat(post(delivery).getStatus()).as("webhook acknowledgement").isEqualTo(202);
        return process(UUID.fromString(delivery.deliveryId()));
    }

    Map<String, Object> process(UUID id) {
        Map<String, Object> before = delivery(id);
        for (int cycle = 0; cycle < MAX_CYCLES && delivery(id).equals(before); cycle++) {
            processor.processBatch();
        }
        return delivery(id);
    }

    /** Runs processor cycles until none of {@code ids} is waiting. */
    void processAll(List<UUID> ids) {
        for (int cycle = 0; cycle < MAX_CYCLES && ids.stream().anyMatch(this::pending); cycle++) {
            processor.processBatch();
        }
    }

    Map<String, Object> delivery(UUID id) {
        return jdbc.queryForMap("SELECT * FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id);
    }

    private boolean pending(UUID id) {
        return "RECEIVED".equals(delivery(id).get("status"));
    }

    void resetCursors() {
        jdbc.update(
                "DELETE FROM git_integration.sync_cursors WHERE name IN (?, ?)",
                HeadReconciler.CURSOR,
                RedeliverySweeper.CURSOR);
    }

    void cleanUp() {
        deliveries.forEach(
                id -> jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id));
        deliveries.clear();
        fixtures.cleanUp();
        resetCursors();
    }

    private Optional<String> headColumn(UUID appId, String column) {
        List<String> values = jdbc.queryForList(
                "SELECT " + column + " FROM git_integration.branch_heads WHERE app_id = ? AND branch = 'main'",
                String.class,
                appId);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }
}
