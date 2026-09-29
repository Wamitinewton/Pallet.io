package io.pallet.gitintegration.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.security.Role;
import io.pallet.gitintegration.support.ConsumerGroups;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.testcontainers.kafka.KafkaContainer;

/** Rebuilding the read model from the compacted topic reproduces it exactly. */
@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class MembershipRebuildIntegrationTest {

    private static final int ORGS = 4;
    private static final int USERS_PER_ORG = 5;
    private static final int VERSIONS_PER_MEMBERSHIP = 10;
    private static final Role[] ROLES = Role.values();

    /** A tombstone replaces the last state record of the first membership, keeping the history at 200 records. */
    private static final int HISTORY_SIZE = ORGS * USERS_PER_ORG * VERSIONS_PER_MEMBERSHIP;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    private record Published(String key, OrgMembershipChanged state) {}

    @Test
    void truncatingAndReplayingTheTopicFromTheStartRestoresTheSameTable() {
        Random random = new Random(19);
        List<String> orgIds = new ArrayList<>();
        List<Deque<Published>> histories = new ArrayList<>();
        for (int o = 0; o < ORGS; o++) {
            String orgId = "org-" + UUID.randomUUID();
            orgIds.add(orgId);
            for (int u = 0; u < USERS_PER_ORG; u++) {
                histories.add(history(orgId, "user-" + UUID.randomUUID(), random));
            }
        }
        Published tombstoned = histories.getFirst().pollLast();
        histories.getFirst().addLast(new Published(tombstoned.key(), null));

        int published = publishInterleaved(histories, random);
        assertThat(published).isEqualTo(HISTORY_SIZE);

        MessageListenerContainer container = listeners.getListenerContainer(MembershipStateListener.LISTENER_ID);
        String groupId = container.getGroupId();
        try (ConsumerGroups groups = new ConsumerGroups(kafka.getBootstrapServers())) {
            groups.awaitCaughtUp(groupId, Topics.ORG_MEMBERSHIP_CHANGED);
            List<Map<String, Object>> snapshot = snapshot(orgIds);
            assertThat(snapshot).hasSize(ORGS * USERS_PER_ORG - 1);

            try {
                container.stop();
                await().atMost(Duration.ofSeconds(30)).until(() -> !container.isRunning());
                jdbc.execute("TRUNCATE git_integration.org_memberships");
                groups.resetToEarliest(groupId, Topics.ORG_MEMBERSHIP_CHANGED);
            } finally {
                container.start();
            }
            groups.awaitCaughtUp(groupId, Topics.ORG_MEMBERSHIP_CHANGED);

            assertThat(snapshot(orgIds)).isEqualTo(snapshot);
        }
    }

    private static Deque<Published> history(String orgId, String userId, Random random) {
        String key = OrgMembershipChanged.key(orgId, userId);
        Deque<Published> history = new ArrayDeque<>();
        for (long version = 1; version <= VERSIONS_PER_MEMBERSHIP; version++) {
            String status = version > 1 && random.nextInt(4) == 0
                    ? OrgMembershipChanged.STATUS_REMOVED
                    : OrgMembershipChanged.STATUS_ACTIVE;
            Role role = ROLES[random.nextInt(ROLES.length)];
            history.addLast(
                    new Published(key, OrgMembershipChanged.of(orgId, userId, role.wireName(), status, version)));
        }
        return history;
    }

    /** Publishes every membership's history in its own order, interleaved across memberships at random. */
    private int publishInterleaved(List<Deque<Published>> histories, Random random) {
        List<Deque<Published>> remaining = new ArrayList<>(histories);
        int published = 0;
        while (!remaining.isEmpty()) {
            Deque<Published> next = remaining.get(random.nextInt(remaining.size()));
            Published record = next.pollFirst();
            if (record.state() == null) {
                publisher.publishTombstone(Topics.ORG_MEMBERSHIP_CHANGED, record.key());
            } else {
                publisher.publish(record.state(), record.key());
            }
            published++;
            if (next.isEmpty()) {
                remaining.remove(next);
            }
        }
        return published;
    }

    private List<Map<String, Object>> snapshot(List<String> orgIds) {
        return jdbc.queryForList(
                "SELECT org_id, user_id, role, status, source_version FROM git_integration.org_memberships"
                        + " WHERE org_id = ANY (?) ORDER BY org_id, user_id",
                (Object) orgIds.toArray(String[]::new));
    }
}
