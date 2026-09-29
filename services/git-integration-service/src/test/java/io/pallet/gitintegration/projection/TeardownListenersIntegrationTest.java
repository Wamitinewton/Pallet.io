package io.pallet.gitintegration.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.support.ReadModelFixtures;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class TeardownListenersIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final long REPO = 42;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private ReadModelFixtures fixtures;
    private long installationId;
    private String orgA;
    private String orgB;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        installationId = fixtures.newInstallation();
        orgA = fixtures.newOrg();
        orgB = fixtures.newOrg();
        fixtures.linkInstallation(installationId, orgA, "ACTIVE");
        fixtures.linkInstallation(installationId, orgB, "ACTIVE");
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void aDeletedAppLosesItsOwnLinkOnlyWithoutANoticeAndOnlyOnce() {
        UUID deleted = fixtures.newRepoLink(orgA, installationId, REPO);
        UUID sibling = fixtures.newRepoLink(orgA, installationId, REPO);
        UUID otherOrg = fixtures.newRepoLink(orgB, installationId, REPO);
        AppDeleted event = AppDeleted.of(orgA, deleted.toString(), "gone", "user-owner");

        publisher.publish(event);

        await().atMost(WAIT).until(() -> "DISCONNECTED".equals(repoLink(deleted).get("status")));
        assertThat(repoLink(deleted)).containsEntry("disconnect_reason", "APP_DELETED");
        assertThat(repoLink(sibling)).containsEntry("status", "ACTIVE");
        assertThat(repoLink(otherOrg)).containsEntry("status", "ACTIVE");
        assertThat(events(orgA, NotificationRequested.TYPE)).isZero();
        assertThat(disconnectAudits(orgA)).isOne();

        double duplicatesBefore = duplicates(AppEventListener.DELETED_CONSUMER);
        publisher.publish(event);

        await().atMost(WAIT).until(() -> duplicates(AppEventListener.DELETED_CONSUMER) > duplicatesBefore);
        assertThat(disconnectAudits(orgA)).isOne();
    }

    @Test
    void aDeletedOrgEndsItsOwnConnectionsWhileAnotherOrgsLinkToTheSameInstallationStays() {
        UUID appA = fixtures.newRepoLink(orgA, installationId, REPO);
        UUID appB = fixtures.newRepoLink(orgB, installationId, REPO);
        OrgDeleted event = OrgDeleted.of(orgA, "user-owner");

        publisher.publish(event);

        await().atMost(WAIT).until(() -> "UNLINKED".equals(linkStatus(orgA)));
        assertThat(repoLink(appA))
                .containsEntry("status", "DISCONNECTED")
                .containsEntry("disconnect_reason", "ORG_DELETED");
        assertThat(repoLink(appB)).containsEntry("status", "ACTIVE");
        assertThat(linkStatus(orgB)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT unused_since FROM git_integration.installations WHERE installation_id = ?",
                        Object.class,
                        installationId))
                .isNull();
        assertThat(events(orgA, NotificationRequested.TYPE)).isZero();
        assertThat(disconnectAudits(orgA)).isOne();

        double duplicatesBefore = duplicates(OrgDeletedListener.CONSUMER);
        publisher.publish(event);

        await().atMost(WAIT).until(() -> duplicates(OrgDeletedListener.CONSUMER) > duplicatesBefore);
        assertThat(disconnectAudits(orgA)).isOne();
        assertThat(linkStatus(orgB)).isEqualTo("ACTIVE");
    }

    private Map<String, Object> repoLink(UUID appId) {
        return jdbc.queryForMap("SELECT * FROM git_integration.repo_links WHERE app_id = ?", appId);
    }

    private String linkStatus(String orgId) {
        return jdbc.queryForObject(
                "SELECT status FROM git_integration.installation_links WHERE installation_id = ? AND org_id = ?",
                String.class,
                installationId,
                orgId);
    }

    private int events(String orgId, String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.outbox_events WHERE org_id = ? AND event_type = ?",
                Integer.class,
                orgId,
                eventType);
    }

    private int disconnectAudits(String orgId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM git_integration.outbox_events
                 WHERE org_id = ? AND event_type = ? AND payload ->> 'action' = ?
                """, Integer.class, orgId, AuditEventRecorded.TYPE, AuditEvents.REPO_LINK_DISCONNECTED);
    }

    private double duplicates(String consumer) {
        Counter counter = meters.find("git." + TransactionalInbox.DUPLICATES)
                .tag(TransactionalInbox.TAG_LISTENER, consumer)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
