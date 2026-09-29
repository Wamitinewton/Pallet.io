package io.pallet.gitintegration.push;

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
 * One installation and one repository, linked by apps in any number of orgs. Pushes go in through the webhook endpoint
 * and out through {@link DeliveryProcessor#processBatch()}; {@link #cleanUp()} removes every row and injected fault.
 */
final class PushScenario {

    private static final int MAX_CYCLES = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    final long installationId;
    final long repoId;

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final DeliveryProcessor processor;
    private final ReadModelFixtures fixtures;
    private final List<UUID> deliveries = new ArrayList<>();
    private final List<String> faults = new ArrayList<>();

    PushScenario(MockMvc mvc, JdbcTemplate jdbc, DeliveryProcessor processor) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.processor = processor;
        this.fixtures = new ReadModelFixtures(jdbc);
        this.installationId = fixtures.newInstallation();
        this.repoId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    static String sha(char c) {
        return String.valueOf(c).repeat(40);
    }

    /** An org that has linked this scenario's installation. */
    String newOrg() {
        String orgId = fixtures.newOrg();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        return orgId;
    }

    /** An active app auto-deploying {@code main} from the repository root, with no head yet. */
    UUID linkApp(String orgId) {
        return fixtures.newRepoLink(orgId, installationId, repoId);
    }

    void rootDirectory(UUID appId, String rootDirectory) {
        jdbc.update("UPDATE git_integration.repo_links SET root_directory = ? WHERE app_id = ?", rootDirectory, appId);
    }

    void autoDeploy(UUID appId, boolean autoDeploy) {
        jdbc.update("UPDATE git_integration.repo_links SET auto_deploy = ? WHERE app_id = ?", autoDeploy, appId);
    }

    void installationStatus(String status) {
        jdbc.update(
                "UPDATE git_integration.installations SET status = ? WHERE installation_id = ?",
                status,
                installationId);
    }

    void head(UUID appId, String sha) {
        jdbc.update(
                "INSERT INTO git_integration.branch_heads (app_id, branch, head_sha) VALUES (?, 'main', ?)",
                appId,
                sha);
    }

    Optional<String> head(UUID appId) {
        return jdbc
                .queryForList(
                        "SELECT head_sha FROM git_integration.branch_heads WHERE app_id = ? AND branch = 'main'",
                        String.class,
                        appId)
                .stream()
                .findFirst();
    }

    WebhookFixtures.Delivery push(String before, String after) {
        return push("push-main.json", before, after);
    }

    WebhookFixtures.Delivery push(String fixture, String before, String after) {
        return WebhookFixtures.delivery(fixture)
                .with("/installation/id", installationId)
                .with("/repository/id", repoId)
                .with("/before", before)
                .with("/after", after)
                .with("/head_commit/id", after);
    }

    /** A push whose only commit changes {@code paths}. */
    WebhookFixtures.Delivery pushChanging(String before, String after, String... paths) {
        return push(before, after)
                .with(
                        "/commits",
                        List.of(Map.of(
                                "id", after, "added", List.of(), "removed", List.of(), "modified", List.of(paths))));
    }

    UUID post(WebhookFixtures.Delivery delivery) {
        MockHttpServletResponse response = delivery.post(mvc);
        assertThat(response.getStatus()).as("webhook acknowledgement").isEqualTo(202);
        UUID id = UUID.fromString(delivery.deliveryId());
        deliveries.add(id);
        return id;
    }

    /** Runs cycles until this delivery has been through one round, whatever its outcome. */
    Map<String, Object> process(UUID id) {
        Map<String, Object> before = delivery(id);
        for (int cycle = 0; cycle < MAX_CYCLES && delivery(id).equals(before); cycle++) {
            processor.processBatch();
        }
        return delivery(id);
    }

    Map<String, Object> postAndProcess(WebhookFixtures.Delivery push) {
        return process(post(push));
    }

    Map<String, Object> delivery(UUID id) {
        return jdbc.queryForMap("SELECT * FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id);
    }

    boolean pending(List<UUID> ids) {
        return ids.stream().anyMatch(id -> "RECEIVED".equals(delivery(id).get("status")));
    }

    void makeDue(UUID id) {
        jdbc.update("UPDATE git_integration.webhook_deliveries SET next_attempt_at = now() WHERE delivery_id = ?", id);
    }

    /** Every {@code GitPushReceived} in the outbox for the org, in the order it was appended. */
    List<Published> published(String orgId) {
        return jdbc.query(
                """
                SELECT event_id, org_id, record_key, payload::text AS payload FROM git_integration.outbox_events
                 WHERE org_id = ? AND event_type = ? ORDER BY id
                """,
                (row, i) -> new Published(
                        row.getObject("event_id", UUID.class),
                        row.getString("org_id"),
                        row.getString("record_key"),
                        JSON.readTree(row.getString("payload"))),
                orgId,
                GitPushReceived.TYPE);
    }

    /** Makes every write to {@code table} matching {@code condition} (a trigger {@code WHEN} clause) fail. */
    void failWrites(String table, String condition) {
        String name = "push_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION git_integration." + name + "() RETURNS trigger LANGUAGE plpgsql AS"
                + " $$ BEGIN RAISE EXCEPTION 'injected failure'; END $$");
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE INSERT OR UPDATE ON git_integration." + table
                + " FOR EACH ROW WHEN (" + condition + ") EXECUTE FUNCTION git_integration." + name + "()");
        faults.add(name + ":" + table);
    }

    void clearFaults() {
        faults.forEach(fault -> {
            String[] parts = fault.split(":");
            jdbc.execute("DROP TRIGGER IF EXISTS " + parts[0] + " ON git_integration." + parts[1]);
            jdbc.execute("DROP FUNCTION IF EXISTS git_integration." + parts[0] + "()");
        });
        faults.clear();
    }

    void cleanUp() {
        clearFaults();
        deliveries.forEach(
                id -> jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id));
        deliveries.clear();
        fixtures.cleanUp();
    }

    record Published(UUID eventId, String orgId, String recordKey, JsonNode payload) {

        String commitSha() {
            return payload.path("commitSha").asString();
        }

        String appId() {
            return payload.path("appId").asString();
        }
    }
}
