package io.pallet.gitintegration.installation;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.session.GitHubUserSessionStore;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class, Tokens.LocalDecoder.class})
class InstallationRaceIntegrationTest {

    private static final int MAX_CYCLES = 20;

    /** The same account and permissions {@code github/api/installation.json} answers with. */
    private static final Map<String, Object> ACCOUNT = Map.of("login", "octo-org", "id", 5001, "type", "Organization");

    private static final Map<String, Object> PERMISSIONS =
            Map.of("checks", "write", "contents", "read", "metadata", "read");

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private GitHubUserSessionStore sessions;

    private ReadModelFixtures fixtures;
    private InstallationApi api;
    private final List<UUID> deliveries = new ArrayList<>();
    private String orgId;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new ReadModelFixtures(jdbc);
        api = new InstallationApi(mvc, json, github);
        orgId = fixtures.newOrg();
        admin = fixtures.newMember(orgId, "admin", "ACTIVE");
        api.as(admin).signIn();
    }

    @AfterEach
    void tearDown() {
        deliveries.forEach(
                id -> jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id));
        fixtures.cleanUp();
        sessions.delete(admin);
        jdbc.update("DELETE FROM git_integration.authorization_states WHERE user_id = ?", admin);
    }

    @Test
    void webhookThenRedirectAndRedirectThenWebhookEndTheSame() throws Exception {
        long webhookFirst = fixtures.newInstallationId();
        long redirectFirst = fixtures.newInstallationId();
        github.stubUserInstallationPages(List.of(List.of(webhookFirst, redirectFirst)));
        github.stubInstallation(webhookFirst);
        github.stubInstallation(redirectFirst);

        assertThat(processCreated(webhookFirst)).isEqualTo("PROCESSED");
        assertThat(api.linkExisting(orgId, webhookFirst).getStatus()).isEqualTo(201);

        assertThat(api.linkExisting(orgId, redirectFirst).getStatus()).isEqualTo(201);
        assertThat(processCreated(redirectFirst)).isEqualTo("PROCESSED");

        assertThat(facts(webhookFirst)).isEqualTo(facts(redirectFirst));
        assertThat(facts(webhookFirst))
                .containsEntry("status", "ACTIVE")
                .containsEntry("account_login", "octo-org")
                .containsEntry("in_use", true);
        assertThat(activeLinks(webhookFirst)).isOne();
        assertThat(activeLinks(redirectFirst)).isOne();
    }

    @Test
    void aCreatedInstallationWithNoLinkStartsUnusedAndALinkClearsIt() throws Exception {
        long installationId = fixtures.newInstallationId();
        github.stubUserInstallationPages(List.of(List.of(installationId)));
        github.stubInstallation(installationId);

        assertThat(processCreated(installationId)).isEqualTo("PROCESSED");
        assertThat(facts(installationId)).containsEntry("in_use", false);
        assertThat(activeLinks(installationId)).isZero();

        assertThat(api.linkExisting(orgId, installationId).getStatus()).isEqualTo(201);

        assertThat(facts(installationId)).containsEntry("in_use", true);
    }

    @Test
    void aRepeatedCreatedWebhookKeepsTheUnusedClockWhereItStarted() {
        long installationId = fixtures.newInstallationId();
        assertThat(processCreated(installationId)).isEqualTo("PROCESSED");
        Object started = unusedSince(installationId);

        assertThat(processCreated(installationId)).isEqualTo("PROCESSED");

        assertThat(unusedSince(installationId)).isEqualTo(started);
    }

    @Test
    void anUnhandledInstallationActionIsIgnoredWithoutRecordingTheInstallation() {
        long installationId = fixtures.newInstallationId();

        assertThat(process(installationId, "unknown_action")).isEqualTo("IGNORED");

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.installations WHERE installation_id = ?",
                        Integer.class,
                        installationId))
                .isZero();
    }

    private String processCreated(long installationId) {
        return process(installationId, "created");
    }

    private String process(long installationId, String action) {
        byte[] body = WebhookFixtures.delivery("installation-created.json")
                .with("/action", action)
                .with("/installation/id", installationId)
                .with("/installation/account", ACCOUNT)
                .with("/installation/permissions", PERMISSIONS)
                .body();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO git_integration.webhook_deliveries
                    (delivery_id, event, action, installation_id, payload, status)
                VALUES (?, 'installation', ?, ?, CAST(? AS jsonb), 'RECEIVED')
                """, id, action, installationId, new String(body, StandardCharsets.UTF_8));
        deliveries.add(id);
        for (int i = 0; i < MAX_CYCLES && "RECEIVED".equals(status(id)); i++) {
            processor.processBatch();
        }
        return status(id);
    }

    private String status(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM git_integration.webhook_deliveries WHERE delivery_id = ?", String.class, id);
    }

    private Map<String, Object> facts(long installationId) {
        return jdbc.queryForMap("""
                SELECT account_id, account_login, account_type, repository_selection, status,
                       permissions::text AS permissions, unused_since IS NULL AS in_use, suspended_at, deleted_at
                  FROM git_integration.installations WHERE installation_id = ?
                """, installationId);
    }

    private Object unusedSince(long installationId) {
        return jdbc.queryForObject(
                "SELECT unused_since FROM git_integration.installations WHERE installation_id = ?",
                Object.class,
                installationId);
    }

    private int activeLinks(long installationId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM git_integration.installation_links
                 WHERE installation_id = ? AND status = 'ACTIVE'
                """, Integer.class, installationId);
    }
}
