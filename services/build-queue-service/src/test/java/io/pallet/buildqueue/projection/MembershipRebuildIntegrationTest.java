package io.pallet.buildqueue.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pallet.buildqueue.security.Role;
import io.pallet.buildqueue.support.ConsumerGroups;
import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
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

/** The rebuild procedure in ARCHITECTURE.md §Authorization model reproduces the read model exactly. */
@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class MembershipRebuildIntegrationTest {

    private static final int ORGS = 5;
    private static final int USERS_PER_ORG = 10;
    private static final int VERSIONS_PER_MEMBERSHIP = 4;
    private static final Role[] ROLES = Role.values();

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
    void truncatingAndReplayingTheTopicFromTheStartLeavesTheLatestVersionOfEachMembership() {
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
        List<Map<String, Object>> expected = latest(histories);

        publishInterleaved(histories, random);

        MessageListenerContainer container = listeners.getListenerContainer(MembershipStateListener.LISTENER_ID);
        String groupId = container.getGroupId();
        try (ConsumerGroups groups = new ConsumerGroups(kafka.getBootstrapServers())) {
            groups.awaitCaughtUp(groupId, Topics.ORG_MEMBERSHIP_CHANGED);
            assertThat(snapshot(orgIds)).isEqualTo(expected);

            try {
                container.stop();
                await().atMost(Duration.ofSeconds(30)).until(() -> !container.isRunning());
                jdbc.execute("TRUNCATE build_queue.org_memberships");
                groups.resetToEarliest(groupId, Topics.ORG_MEMBERSHIP_CHANGED);
            } finally {
                container.start();
            }
            groups.awaitCaughtUp(groupId, Topics.ORG_MEMBERSHIP_CHANGED);

            assertThat(snapshot(orgIds)).isEqualTo(expected);
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

    /** The row each history should leave behind, in the snapshot's order; a history ending in a tombstone leaves none. */
    private static List<Map<String, Object>> latest(List<Deque<Published>> histories) {
        return histories.stream()
                .map(Deque::peekLast)
                .filter(last -> last.state() != null)
                .map(Published::state)
                .sorted((a, b) -> a.orgId().equals(b.orgId())
                        ? a.userId().compareTo(b.userId())
                        : a.orgId().compareTo(b.orgId()))
                .map(state -> Map.<String, Object>of(
                        "org_id", state.orgId(),
                        "user_id", state.userId(),
                        "role", state.role(),
                        "status", state.status(),
                        "source_version", state.membershipVersion()))
                .toList();
    }

    /** Publishes every membership's history in its own order, interleaved across memberships at random. */
    private void publishInterleaved(List<Deque<Published>> histories, Random random) {
        List<Deque<Published>> remaining = new ArrayList<>();
        histories.forEach(history -> remaining.add(new ArrayDeque<>(history)));
        while (!remaining.isEmpty()) {
            Deque<Published> next = remaining.get(random.nextInt(remaining.size()));
            Published record = next.pollFirst();
            if (record.state() == null) {
                publisher.publishTombstone(Topics.ORG_MEMBERSHIP_CHANGED, record.key());
            } else {
                publisher.publish(record.state(), record.key());
            }
            if (next.isEmpty()) {
                remaining.remove(next);
            }
        }
    }

    private List<Map<String, Object>> snapshot(List<String> orgIds) {
        return jdbc.queryForList(
                "SELECT org_id, user_id, role, status, source_version FROM build_queue.org_memberships"
                        + " WHERE org_id = ANY (?) ORDER BY org_id COLLATE \"C\", user_id COLLATE \"C\"",
                (Object) orgIds.toArray(String[]::new));
    }
}
