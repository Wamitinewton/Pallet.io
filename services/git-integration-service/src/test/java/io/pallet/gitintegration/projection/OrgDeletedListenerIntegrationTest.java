package io.pallet.gitintegration.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.projection.MembershipProjection.Status;
import io.pallet.gitintegration.security.Role;
import io.pallet.gitintegration.support.ConsumerGroups;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.kafka.KafkaContainer;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class OrgDeletedListenerIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private MembershipProjectionRepository memberships;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private KafkaContainer kafka;

    private ConsumerGroups groups;
    private String orgId;

    @BeforeEach
    void setUp() {
        groups = new ConsumerGroups(kafka.getBootstrapServers());
        orgId = "org-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        groups.close();
    }

    @Test
    void everyMembershipBecomesRemovedWithItsVersionUntouchedAndTheOrgIsRecorded() {
        String owner = member(Role.OWNER, 4);
        String developer = member(Role.DEVELOPER, 1);
        Instant deletedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        OrgDeleted event = new OrgDeleted(UUID.randomUUID(), OrgDeleted.TYPE, orgId, deletedAt, owner);

        publisher.publish(event);

        awaitOrgDeleted();
        assertThat(jdbc.queryForObject(
                                "SELECT deleted_at FROM git_integration.deleted_orgs WHERE org_id = ?",
                                Timestamp.class,
                                orgId)
                        .toInstant())
                .isEqualTo(deletedAt);
        assertMembership(owner, Role.OWNER, Status.REMOVED, 4);
        assertMembership(developer, Role.DEVELOPER, Status.REMOVED, 1);
    }

    @Test
    void theAuthoritativeRemovedRecordWithAHigherVersionStillApplies() {
        String developer = member(Role.DEVELOPER, 1);
        publisher.publish(OrgDeleted.of(orgId, "owner"));
        awaitOrgDeleted();

        applyState(developer, Role.DEVELOPER, OrgMembershipChanged.STATUS_REMOVED, 2);

        assertMembership(developer, Role.DEVELOPER, Status.REMOVED, 2);
    }

    @Test
    void replayingTheSameOrgDeletedIsANoOp() {
        String developer = member(Role.DEVELOPER, 1);
        OrgDeleted event = OrgDeleted.of(orgId, "owner");
        publisher.publish(event);
        awaitOrgDeleted();
        applyState(developer, Role.DEVELOPER, OrgMembershipChanged.STATUS_ACTIVE, 2);
        double duplicatesBefore = duplicates();

        publisher.publish(event);

        await().atMost(WAIT).until(() -> duplicates() > duplicatesBefore);
        assertMembership(developer, Role.DEVELOPER, Status.ACTIVE, 2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.deleted_orgs WHERE org_id = ?", Integer.class, orgId))
                .isOne();
    }

    private String member(Role role, long version) {
        String userId = "user-" + UUID.randomUUID();
        applyState(userId, role, OrgMembershipChanged.STATUS_ACTIVE, version);
        assertMembership(userId, role, Status.ACTIVE, version);
        return userId;
    }

    private void applyState(String userId, Role role, String status, long version) {
        publisher.publish(
                OrgMembershipChanged.of(orgId, userId, role.wireName(), status, version),
                OrgMembershipChanged.key(orgId, userId));
        groups.awaitCaughtUp(
                listeners
                        .getListenerContainer(MembershipStateListener.LISTENER_ID)
                        .getGroupId(),
                Topics.ORG_MEMBERSHIP_CHANGED);
    }

    private void awaitOrgDeleted() {
        await().atMost(WAIT)
                .until(() -> jdbc.queryForObject(
                                "SELECT count(*) FROM git_integration.deleted_orgs WHERE org_id = ?",
                                Integer.class,
                                orgId)
                        == 1);
    }

    private void assertMembership(String userId, Role role, Status status, long version) {
        MembershipProjection membership =
                memberships.findMembership(orgId, userId).orElseThrow();
        assertThat(membership.role()).isEqualTo(role);
        assertThat(membership.status()).isEqualTo(status);
        assertThat(membership.sourceVersion()).isEqualTo(version);
    }

    private double duplicates() {
        Counter counter = meterRegistry
                .find("git." + TransactionalInbox.DUPLICATES)
                .tag(TransactionalInbox.TAG_LISTENER, OrgDeletedListener.CONSUMER)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
