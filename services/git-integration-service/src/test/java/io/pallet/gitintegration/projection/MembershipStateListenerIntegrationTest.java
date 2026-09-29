package io.pallet.gitintegration.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.projection.MembershipProjection.Status;
import io.pallet.gitintegration.security.Role;
import io.pallet.gitintegration.support.ConsumerGroups;
import io.pallet.gitintegration.support.TopicProbe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class MembershipStateListenerIntegrationTest {

    private static final Role[] ROLES = Role.values();

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private MembershipProjectionRepository memberships;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private KafkaContainer kafka;

    private ConsumerGroups groups;
    private String orgId;
    private String userId;

    @BeforeEach
    void setUp() {
        groups = new ConsumerGroups(kafka.getBootstrapServers());
        orgId = "org-" + UUID.randomUUID();
        userId = "user-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        groups.close();
    }

    @Test
    void stateRecordsApplyOnlyWhenTheirVersionIsNewer() {
        applyAll(state(Role.DEVELOPER, OrgMembershipChanged.STATUS_ACTIVE, 1));
        assertMembership(Role.DEVELOPER, Status.ACTIVE, 1);

        applyAll(state(Role.ADMIN, OrgMembershipChanged.STATUS_ACTIVE, 2));
        assertMembership(Role.ADMIN, Status.ACTIVE, 2);

        applyAll(state(Role.VIEWER, OrgMembershipChanged.STATUS_ACTIVE, 1));
        assertMembership(Role.ADMIN, Status.ACTIVE, 2);

        applyAll(state(Role.OWNER, OrgMembershipChanged.STATUS_ACTIVE, 2));
        assertMembership(Role.ADMIN, Status.ACTIVE, 2);

        applyAll(state(Role.ADMIN, OrgMembershipChanged.STATUS_REMOVED, 3));
        assertMembership(Role.ADMIN, Status.REMOVED, 3);
    }

    @Test
    void aTombstoneDeletesTheMembership() {
        applyAll(state(Role.DEVELOPER, OrgMembershipChanged.STATUS_ACTIVE, 1));
        assertMembership(Role.DEVELOPER, Status.ACTIVE, 1);

        publisher.publishTombstone(Topics.ORG_MEMBERSHIP_CHANGED, OrgMembershipChanged.key(orgId, userId));
        awaitCaughtUp();

        assertThat(memberships.findMembership(orgId, userId)).isEmpty();
    }

    @Test
    void fiftyShuffledRecordsForOneMembershipEndAtTheHighestVersion() {
        List<OrgMembershipChanged> history = new ArrayList<>(LongStream.rangeClosed(1, 50)
                .mapToObj(version -> state(roleFor(version), statusFor(version), version))
                .toList());
        Collections.shuffle(history, new Random(42));

        applyAll(history.toArray(OrgMembershipChanged[]::new));

        assertMembership(roleFor(50), Status.valueOf(statusFor(50)), 50);
    }

    @Test
    void fiftyConcurrentTransactionsForOneMembershipEndAtTheHighestVersion() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        List<Long> versions =
                new ArrayList<>(LongStream.rangeClosed(1, 50).boxed().toList());
        Collections.shuffle(versions, new Random(7));
        List<Future<Integer>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (long version : versions) {
                results.add(pool.submit(() -> transaction.execute(status -> memberships.applyState(
                        orgId, userId, roleFor(version).wireName(), statusFor(version), version))));
            }
            for (Future<Integer> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        }

        assertMembership(roleFor(50), Status.valueOf(statusFor(50)), 50);
    }

    @Test
    void aMalformedKeyIsDeadLettered() {
        String badKey = orgId + ":" + userId + ":extra";

        try (TopicProbe probe = dltProbe()) {
            publisher.publish(state(Role.VIEWER, OrgMembershipChanged.STATUS_ACTIVE, 1), badKey);

            assertThat(probe.awaitKey(badKey, 1)).hasSize(1);
        }
        assertThat(memberships.findMembership(orgId, userId)).isEmpty();
    }

    @Test
    void aTombstoneWithAMalformedKeyIsDeadLettered() {
        String badKey = orgId + userId;

        try (TopicProbe probe = dltProbe()) {
            publisher.publishTombstone(Topics.ORG_MEMBERSHIP_CHANGED, badKey);

            assertThat(probe.awaitKey(badKey, 1)).hasSize(1);
        }
    }

    @Test
    void aPayloadThatDisagreesWithItsKeyIsDeadLettered() {
        String otherUsersKey = OrgMembershipChanged.key(orgId, "user-" + UUID.randomUUID());

        try (TopicProbe probe = dltProbe()) {
            publisher.publish(state(Role.OWNER, OrgMembershipChanged.STATUS_ACTIVE, 1), otherUsersKey);

            assertThat(probe.awaitKey(otherUsersKey, 1)).hasSize(1);
        }
        assertThat(memberships.findMembership(orgId, userId)).isEmpty();
    }

    @Test
    void anUnknownRoleIsDeadLetteredNeverMapped() {
        OrgMembershipChanged superuser = new OrgMembershipChanged(
                UUID.randomUUID(),
                OrgMembershipChanged.TYPE,
                orgId,
                Instant.now(),
                userId,
                "OWNER",
                OrgMembershipChanged.STATUS_ACTIVE,
                1);
        String key = OrgMembershipChanged.key(orgId, userId);

        try (TopicProbe probe = dltProbe()) {
            publisher.publish(superuser, key);

            assertThat(probe.awaitKey(key, 1)).hasSize(1);
        }
        assertThat(memberships.findMembership(orgId, userId)).isEmpty();
    }

    @Test
    void theProjectionLagIsMeasuredFromOccurredAt() {
        Timer lag = meterRegistry.find(ProjectionMetrics.PROJECTION_LAG).timer();
        assertThat(lag).isNotNull();
        long countBefore = lag.count();
        double secondsBefore = lag.totalTime(TimeUnit.SECONDS);
        OrgMembershipChanged fiveSecondsOld = new OrgMembershipChanged(
                UUID.randomUUID(),
                OrgMembershipChanged.TYPE,
                orgId,
                Instant.now().minusSeconds(5),
                userId,
                Role.VIEWER.wireName(),
                OrgMembershipChanged.STATUS_ACTIVE,
                1);

        applyAll(fiveSecondsOld);

        assertThat(lag.count()).isGreaterThan(countBefore);
        assertThat(lag.totalTime(TimeUnit.SECONDS) - secondsBefore).isGreaterThanOrEqualTo(5.0);
    }

    private void applyAll(OrgMembershipChanged... records) {
        for (OrgMembershipChanged record : records) {
            publisher.publish(record, OrgMembershipChanged.key(record.orgId(), record.userId()));
        }
        awaitCaughtUp();
    }

    private void awaitCaughtUp() {
        String groupId = listeners
                .getListenerContainer(MembershipStateListener.LISTENER_ID)
                .getGroupId();
        groups.awaitCaughtUp(groupId, Topics.ORG_MEMBERSHIP_CHANGED);
    }

    private void assertMembership(Role role, Status status, long version) {
        MembershipProjection membership =
                memberships.findMembership(orgId, userId).orElseThrow();
        assertThat(membership.role()).isEqualTo(role);
        assertThat(membership.status()).isEqualTo(status);
        assertThat(membership.sourceVersion()).isEqualTo(version);
    }

    private TopicProbe dltProbe() {
        return new TopicProbe(kafka.getBootstrapServers(), Topics.deadLetter(Topics.ORG_MEMBERSHIP_CHANGED));
    }

    private OrgMembershipChanged state(Role role, String status, long version) {
        return OrgMembershipChanged.of(orgId, userId, role.wireName(), status, version);
    }

    private static Role roleFor(long version) {
        return ROLES[(int) (version % ROLES.length)];
    }

    private static String statusFor(long version) {
        return version % 5 == 0 ? OrgMembershipChanged.STATUS_REMOVED : OrgMembershipChanged.STATUS_ACTIVE;
    }
}
