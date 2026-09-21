package io.pallet.orgteam.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.Topics;
import io.pallet.common.events.UserProfileUpdated;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.outbox.TopicProbe;
import io.pallet.orgteam.security.OrgFixtures;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
@TestPropertySource(
        properties = {
            "pallet.orgteam.outbox.enabled=true",
            "pallet.messaging.retry.max-attempts=6",
            "pallet.messaging.retry.initial-interval=500ms",
            "pallet.messaging.retry.max-interval=1s"
        })
class UserProfileUpdatedListenerIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration QUIET = Duration.ofSeconds(3);
    private static final String DEAD_LETTER = Topics.deadLetter(Topics.USER_PROFILE_UPDATED);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JsonMapper jsonMapper;

    private OrgFixtures fixtures;
    private final List<String> orgIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new OrgFixtures(jdbc);
    }

    @AfterEach
    void cleanUp() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.invites WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId);
        });
        fixtures.cleanUp();
        orgIds.clear();
    }

    private String newOrg() {
        String orgId = fixtures.newActiveOrg();
        orgIds.add(orgId);
        return orgId;
    }

    private String newSyncedMember(String orgId, Instant syncedAt) {
        String userId = fixtures.newMember(orgId, "DEVELOPER", "ACTIVE");
        jdbc.update(
                "UPDATE org_team.memberships SET profile_synced_at = ? WHERE org_id = ? AND user_id = ?",
                Timestamp.from(syncedAt),
                orgId,
                userId);
        return userId;
    }

    private static UserProfileUpdated profile(String orgId, String userId, Instant at, String name) {
        return new UserProfileUpdated(
                UUID.randomUUID(), UserProfileUpdated.TYPE, orgId, at, userId, userId + "@example.com", name);
    }

    private TopicProbe probe(String... topics) {
        return new TopicProbe(kafka.getBootstrapServers(), jsonMapper, topics);
    }

    private String displayName(String orgId, String userId) {
        return jdbc.queryForObject(
                "SELECT display_name FROM org_team.memberships WHERE org_id = ? AND user_id = ?",
                String.class,
                orgId,
                userId);
    }

    private void awaitDisplayName(String orgId, String userId, String expected) {
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(displayName(orgId, userId)).isEqualTo(expected));
    }

    private void awaitProcessed(UserProfileUpdated event) {
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM org_team.processed_events WHERE event_id = ?",
                                Integer.class,
                                event.eventId()))
                        .isEqualTo(1));
    }

    private int outboxCount(String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ?", Integer.class, orgId);
    }

    @Test
    void aProfileUpdateRenamesAnExistingMember() {
        String orgId = newOrg();
        String userId = newSyncedMember(orgId, Instant.now().minusSeconds(60));

        publisher.publish(profile(orgId, userId, Instant.now(), "Renamed Member"));

        awaitDisplayName(orgId, userId, "Renamed Member");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT email, profile_synced_at FROM org_team.memberships WHERE org_id = ? AND user_id = ?",
                orgId,
                userId);
        assertThat(row.get("profile_synced_at")).isNotNull();
        assertThat(outboxCount(orgId)).isZero();
    }

    @Test
    void aProfileUpdateThatOvertakesTheAcceptIsRetriedAndWinsOverIt() {
        String orgId = newOrg();
        String email = "invitee-" + UUID.randomUUID() + "@example.com";
        String userId = "user-" + UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO org_team.invites (id, org_id, email, role, invited_by_user_id, status, expires_at)
                        VALUES (?, ?, ?, 'VIEWER', 'inviter', 'PENDING', now() + interval '1 hour')
                        """, inviteId, orgId, email);
        Instant acceptedAt = Instant.now().minusSeconds(30);

        publisher.publish(new UserProfileUpdated(
                UUID.randomUUID(),
                UserProfileUpdated.TYPE,
                orgId,
                acceptedAt.plusSeconds(20),
                userId,
                email,
                "Profile Name"));
        publisher.publish(new OrgInviteAccepted(
                UUID.randomUUID(),
                OrgInviteAccepted.TYPE,
                orgId,
                acceptedAt,
                inviteId.toString(),
                userId,
                email,
                "Accept Name",
                "viewer"));

        awaitDisplayName(orgId, userId, "Profile Name");
    }

    @Test
    void aNewerEventFollowedByAnOlderOneKeepsTheNewer() {
        String orgId = newOrg();
        Instant base = Instant.now().minusSeconds(120);
        String userId = newSyncedMember(orgId, base);

        UserProfileUpdated newer = profile(orgId, userId, base.plusSeconds(60), "Newer");
        UserProfileUpdated older = profile(orgId, userId, base.plusSeconds(30), "Older");
        publisher.publish(newer);
        publisher.publish(older);
        awaitProcessed(older);

        assertThat(displayName(orgId, userId)).isEqualTo("Newer");
    }

    @Test
    void redeliveringAnEventChangesNothingAndDeadLettersNothing() {
        String orgId = newOrg();
        String userId = newSyncedMember(orgId, Instant.now().minusSeconds(60));
        String sentinelUser = newSyncedMember(orgId, Instant.now().minusSeconds(60));
        UserProfileUpdated event = profile(orgId, userId, Instant.now(), "Once");
        UserProfileUpdated sentinel = profile(orgId, sentinelUser, Instant.now(), "Sentinel");

        try (TopicProbe deadLetters = probe(DEAD_LETTER)) {
            publisher.publish(event);
            awaitDisplayName(orgId, userId, "Once");
            publisher.publish(event);
            publisher.publish(sentinel);
            awaitProcessed(sentinel);

            assertThat(deadLetters.observe(orgId, QUIET)).isEmpty();
        }
        assertThat(displayName(orgId, userId)).isEqualTo("Once");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.processed_events WHERE event_id = ?",
                        Integer.class,
                        event.eventId()))
                .isEqualTo(1);
    }

    @Test
    void aMembershipThatNeverAppearsEndsOnTheDeadLetterTopicWithNothingCreated() {
        String orgId = newOrg();
        String userId = "user-" + UUID.randomUUID();
        UserProfileUpdated event = profile(orgId, userId, Instant.now(), "Ghost");

        try (TopicProbe deadLetters = probe(DEAD_LETTER)) {
            publisher.publish(event);

            assertThat(deadLetters.awaitCount(orgId, 1)).hasSize(1);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.memberships WHERE org_id = ? AND user_id = ?",
                        Integer.class,
                        orgId,
                        userId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.processed_events WHERE event_id = ?",
                        Integer.class,
                        event.eventId()))
                .isZero();
    }

    @Test
    void aRemovedMemberAndADeletedOrgAreAcknowledgedWithoutWriting() {
        String orgId = newOrg();
        String removed = newSyncedMember(orgId, Instant.now().minusSeconds(60));
        fixtures.setStatus(orgId, removed, "REMOVED");
        String deletedOrgId = newOrg();
        String orphan = newSyncedMember(deletedOrgId, Instant.now().minusSeconds(60));
        fixtures.setStatus(deletedOrgId, orphan, "REMOVED");
        jdbc.update("UPDATE org_team.organizations SET status = 'DELETED' WHERE org_id = ?", deletedOrgId);
        String before = displayName(orgId, removed);
        UserProfileUpdated forRemoved = profile(orgId, removed, Instant.now(), "Ignored");
        UserProfileUpdated forDeletedOrg = profile(deletedOrgId, orphan, Instant.now(), "Ignored");

        try (TopicProbe deadLetters = probe(DEAD_LETTER)) {
            publisher.publish(forRemoved);
            publisher.publish(forDeletedOrg);
            awaitProcessed(forRemoved);
            awaitProcessed(forDeletedOrg);

            assertThat(deadLetters.observe(orgId, QUIET)).isEmpty();
            assertThat(deadLetters.observe(deletedOrgId, Duration.ofMillis(200)))
                    .isEmpty();
        }
        assertThat(displayName(orgId, removed)).isEqualTo(before);
        assertThat(displayName(deletedOrgId, orphan)).isNotEqualTo("Ignored");
    }
}
