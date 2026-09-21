package io.pallet.orgteam.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.outbox.TopicProbe;
import io.pallet.orgteam.outbox.TopicProbe.Received;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
@TestPropertySource(properties = "pallet.orgteam.outbox.enabled=true")
class OrgProvisionedListenerIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration QUIET = Duration.ofSeconds(2);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @MockitoSpyBean
    private JsonMapper jsonMapper;

    private final List<String> orgIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.memberships WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.organizations WHERE org_id = ?", orgId);
        });
    }

    @Test
    void anOrgProvisionedEventCreatesTheOrgAndItsOwnerAndRelaysBothEvents() {
        String orgId = newOrgId();
        OrgProvisioned event = provisioned(orgId, slugFor(orgId), "Owner@Example.com", "Olivia Owner");

        try (TopicProbe probe = probe(Topics.ORG_MEMBER_ADDED, Topics.AUDIT_EVENT_RECORDED)) {
            publisher.publish(event);

            await().atMost(WAIT).untilAsserted(() -> assertThat(orgCount(orgId)).isEqualTo(1));
            List<Received> relayed = probe.awaitCount(orgId, 2);

            assertThat(relayed)
                    .extracting(Received::topic)
                    .containsExactlyInAnyOrder(Topics.ORG_MEMBER_ADDED, Topics.AUDIT_EVENT_RECORDED);
            Received added = relayed.stream()
                    .filter(r -> r.topic().equals(Topics.ORG_MEMBER_ADDED))
                    .findFirst()
                    .orElseThrow();
            assertThat(added.body().get("userId").asString()).isEqualTo(event.ownerUserId());
            assertThat(added.body().get("email").asString()).isEqualTo("owner@example.com");
            Received audit = relayed.stream()
                    .filter(r -> r.topic().equals(Topics.AUDIT_EVENT_RECORDED))
                    .findFirst()
                    .orElseThrow();
            assertThat(audit.body().get("action").asString()).isEqualTo("org.created");
            assertThat(audit.body().get("actor").asString()).isEqualTo(event.ownerUserId());
            assertThat(audit.body().get("resource").asString()).isEqualTo(orgId);
        }

        Map<String, Object> org = jdbc.queryForMap(
                "SELECT name, slug, owner_user_id, status FROM org_team.organizations WHERE org_id = ?", orgId);
        assertThat(org)
                .containsEntry("name", "Acme " + orgId)
                .containsEntry("slug", slugFor(orgId))
                .containsEntry("owner_user_id", event.ownerUserId())
                .containsEntry("status", "ACTIVE");
        Map<String, Object> owner = jdbc.queryForMap(
                "SELECT email, display_name, role, status, profile_synced_at FROM org_team.memberships"
                        + " WHERE org_id = ? AND user_id = ?",
                orgId,
                event.ownerUserId());
        assertThat(owner)
                .containsEntry("email", "owner@example.com")
                .containsEntry("display_name", "Olivia Owner")
                .containsEntry("role", "OWNER")
                .containsEntry("status", "ACTIVE");
        assertThat(owner.get("profile_synced_at")).isNotNull();
    }

    @Test
    void redeliveringTheIdenticalEventChangesNothingAndEmitsNoSecondMemberAdded() {
        String orgId = newOrgId();
        OrgProvisioned event = provisioned(orgId, slugFor(orgId), "owner@example.com", "Olivia");

        try (TopicProbe probe = probe(Topics.ORG_MEMBER_ADDED)) {
            publisher.publish(event);
            await().atMost(WAIT).untilAsserted(() -> assertThat(orgCount(orgId)).isEqualTo(1));
            publisher.publish(event);
            publisher.publish(event);

            List<Received> added = probe.observe(orgId, QUIET.plusSeconds(3));

            assertThat(added).hasSize(1);
        }
        assertThat(orgCount(orgId)).isEqualTo(1);
        assertThat(count("memberships", orgId)).isEqualTo(1);
        assertThat(outboxCount(orgId, OrgMemberAdded.TYPE)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.processed_events WHERE event_id = ?",
                        Integer.class,
                        event.eventId()))
                .isEqualTo(1);
    }

    @Test
    void aReplayUnderADifferentEventIdIsCaughtByTheOrgPrimaryKey() {
        String orgId = newOrgId();
        String slug = slugFor(orgId);

        try (TopicProbe probe = probe(Topics.ORG_MEMBER_ADDED)) {
            publisher.publish(provisioned(orgId, slug, "owner@example.com", "Olivia"));
            await().atMost(WAIT).untilAsserted(() -> assertThat(orgCount(orgId)).isEqualTo(1));
            OrgProvisioned replay = provisioned(orgId, slug, "owner@example.com", "Olivia");
            publisher.publish(replay);
            await().atMost(WAIT)
                    .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM org_team.processed_events WHERE event_id = ?",
                                    Integer.class,
                                    replay.eventId()))
                            .isEqualTo(1));

            assertThat(probe.observe(orgId, QUIET)).hasSize(1);
        }
        assertThat(count("memberships", orgId)).isEqualTo(1);
        assertThat(outboxCount(orgId, OrgMemberAdded.TYPE)).isEqualTo(1);
    }

    @Test
    void aFailureAfterTheOrgInsertRollsEverythingBackAndTheRedeliverySucceeds() {
        String orgId = newOrgId();
        OrgProvisioned event = provisioned(orgId, slugFor(orgId), "owner@example.com", "Olivia");
        doThrow(new IllegalStateException("crash before commit"))
                .doCallRealMethod()
                .when(jsonMapper)
                .writeValueAsString(any());

        publisher.publish(event);

        await().atMost(WAIT).untilAsserted(() -> assertThat(orgCount(orgId)).isEqualTo(1));
        assertThat(count("memberships", orgId)).isEqualTo(1);
        assertThat(outboxCount(orgId, OrgMemberAdded.TYPE)).isEqualTo(1);
        assertThat(outboxCount(orgId, AuditEventRecorded.TYPE)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.processed_events WHERE event_id = ?",
                        Integer.class,
                        event.eventId()))
                .isEqualTo(1);
    }

    @Test
    void aMalformedEventIsDeadLetteredWithNothingPersisted() {
        String orgId = newOrgId();
        OrgProvisioned event = provisioned(orgId, "Not A Valid Slug", "owner@example.com", "Olivia");

        try (TopicProbe probe = probe(Topics.deadLetter(Topics.ORG_PROVISIONED))) {
            publisher.publish(event);

            List<Received> deadLettered = probe.awaitCount(orgId, 1);

            assertThat(deadLettered).hasSize(1);
        }
        assertThat(orgCount(orgId)).isZero();
        assertThat(count("memberships", orgId)).isZero();
        assertThat(outboxCount(orgId, OrgMemberAdded.TYPE)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.processed_events WHERE event_id = ?",
                        Integer.class,
                        event.eventId()))
                .isZero();
    }

    @Test
    void aSlugClaimedByAnotherOrgIsDeadLetteredAndLeavesTheFirstOrgIntact() {
        String firstOrgId = newOrgId();
        String secondOrgId = newOrgId();
        String slug = slugFor(firstOrgId);
        OrgProvisioned first = provisioned(firstOrgId, slug, "first@example.com", "First");
        OrgProvisioned colliding = provisioned(secondOrgId, slug, "second@example.com", "Second");

        try (TopicProbe probe = probe(Topics.deadLetter(Topics.ORG_PROVISIONED))) {
            publisher.publish(first);
            await().atMost(WAIT)
                    .untilAsserted(() -> assertThat(orgCount(firstOrgId)).isEqualTo(1));
            publisher.publish(colliding);

            assertThat(probe.awaitCount(secondOrgId, 1)).hasSize(1);
        }
        assertThat(orgCount(secondOrgId)).isZero();
        assertThat(count("memberships", secondOrgId)).isZero();
        assertThat(orgCount(firstOrgId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT owner_user_id FROM org_team.organizations WHERE org_id = ?", String.class, firstOrgId))
                .isEqualTo(first.ownerUserId());
    }

    private TopicProbe probe(String... topics) {
        return new TopicProbe(kafka.getBootstrapServers(), jsonMapper, topics);
    }

    private String newOrgId() {
        String orgId = "org-" + UUID.randomUUID();
        orgIds.add(orgId);
        return orgId;
    }

    private static String slugFor(String orgId) {
        return "acme-" + orgId.substring(4, 12);
    }

    private static OrgProvisioned provisioned(String orgId, String slug, String email, String displayName) {
        return OrgProvisioned.of(orgId, "Acme " + orgId, slug, "user-" + UUID.randomUUID(), email, displayName);
    }

    private int orgCount(String orgId) {
        return count("organizations", orgId);
    }

    private int count(String table, String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team." + table + " WHERE org_id = ?", Integer.class, orgId);
    }

    private int outboxCount(String orgId, String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ? AND event_type = ?",
                Integer.class,
                orgId,
                eventType);
    }
}
