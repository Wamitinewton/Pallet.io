package io.pallet.gitintegration.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.github.GitHubUserToken;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class AuthorizationRevokedHandlerIntegrationTest {

    private static final int MAX_CYCLES = 20;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private GitHubUserSessionStore store;

    @Autowired
    private JdbcTemplate jdbc;

    private ReadModelFixtures fixtures;
    private final List<UUID> deliveries = new ArrayList<>();
    private final List<String> subjects = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
    }

    @AfterEach
    void tearDown() {
        deliveries.forEach(
                id -> jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id));
        subjects.forEach(sub -> {
            store.delete(sub);
            jdbc.update(
                    "DELETE FROM git_integration.outbox_events WHERE event_type = ? AND payload ->> 'actor' = ?",
                    AuditEventRecorded.TYPE,
                    sub);
        });
        fixtures.cleanUp();
    }

    @Test
    void aRevocationEndsTheUsersSessionAndAuditsItInEachActiveOrg() {
        long githubUserId = newGithubUserId();
        String activeOrg = fixtures.newOrg();
        String removedOrg = fixtures.newOrg();
        String deletedOrg = fixtures.newOrg();
        String sub = newSubject();
        fixtures.addMember(activeOrg, sub, "developer", "ACTIVE");
        fixtures.addMember(removedOrg, sub, "developer", "REMOVED");
        fixtures.addMember(deletedOrg, sub, "developer", "ACTIVE");
        fixtures.deleteOrg(deletedOrg);
        String bystander = newSubject();
        store.save(sub, session(githubUserId));
        store.save(bystander, session(newGithubUserId()));

        UUID delivery = insertRevocation(githubUserId);
        processUntilHandled(delivery);

        assertThat(status(delivery)).isEqualTo("PROCESSED");
        assertThat(store.find(sub)).isEmpty();
        assertThat(store.find(bystander)).isPresent();
        List<Map<String, Object>> audits = audits(sub);
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst())
                .containsEntry("org_id", activeOrg)
                .containsEntry("action", AuditEvents.GITHUB_AUTHORIZATION_REVOKED)
                .containsEntry("github_user_id", String.valueOf(githubUserId));
    }

    @Test
    void aRevocationForAUserWithoutASessionStillProcesses() {
        UUID delivery = insertRevocation(newGithubUserId());

        processUntilHandled(delivery);

        assertThat(status(delivery)).isEqualTo("PROCESSED");
    }

    @Test
    void anotherActionIsIgnored() {
        long githubUserId = newGithubUserId();
        String sub = newSubject();
        store.save(sub, session(githubUserId));

        UUID delivery = insert(githubUserId, "granted");
        processUntilHandled(delivery);

        assertThat(status(delivery)).isEqualTo("IGNORED");
        assertThat(store.find(sub)).isPresent();
    }

    private UUID insertRevocation(long githubUserId) {
        return insert(githubUserId, "revoked");
    }

    private UUID insert(long githubUserId, String action) {
        byte[] body = WebhookFixtures.delivery("github-app-authorization-revoked.json")
                .with("/sender/id", githubUserId)
                .with("/action", action)
                .body();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO git_integration.webhook_deliveries (delivery_id, event, payload, status)
                VALUES (?, 'github_app_authorization', CAST(? AS jsonb), 'RECEIVED')
                """, id, new String(body, StandardCharsets.UTF_8));
        deliveries.add(id);
        return id;
    }

    private void processUntilHandled(UUID id) {
        for (int i = 0; i < MAX_CYCLES && "RECEIVED".equals(status(id)); i++) {
            processor.processBatch();
        }
    }

    private String status(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM git_integration.webhook_deliveries WHERE delivery_id = ?", String.class, id);
    }

    private List<Map<String, Object>> audits(String sub) {
        return jdbc.queryForList("""
                SELECT org_id, payload ->> 'action' AS action, payload -> 'context' ->> 'githubUserId' AS github_user_id
                  FROM git_integration.outbox_events
                 WHERE event_type = ? AND payload ->> 'actor' = ?
                """, AuditEventRecorded.TYPE, sub);
    }

    private String newSubject() {
        String sub = "user-" + UUID.randomUUID();
        subjects.add(sub);
        return sub;
    }

    private static GitHubUserSession session(long githubUserId) {
        return new GitHubUserSession(
                new GitHubUserToken("ghu_" + UUID.randomUUID()),
                githubUserId,
                "fixture-dev",
                Instant.now().plus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MILLIS));
    }

    private static long newGithubUserId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }
}
