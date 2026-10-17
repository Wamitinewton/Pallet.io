package io.pallet.buildqueue.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.buildqueue.projection.MembershipProjection.Status;
import io.pallet.buildqueue.security.Role;
import io.pallet.buildqueue.support.ConsumerGroups;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.events.Topics;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
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
    private OrgProjectionRepository orgs;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

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
    void theOrgIsDeletedAndEveryMembershipRemovedWithItsVersionUntouched() {
        String owner = member(Role.OWNER, 4);
        String developer = member(Role.DEVELOPER, 1);

        publisher.publish(OrgDeleted.of(orgId, owner));

        OrgProjection org = awaitOrgDeleted();
        assertThat(org.deletedAt()).isNotNull();
        assertMembership(owner, Role.OWNER, Status.REMOVED, 4);
        assertMembership(developer, Role.DEVELOPER, Status.REMOVED, 1);
    }

    @Test
    void anUnknownOrgIsRecordedAsDeleted() {
        publisher.publish(OrgDeleted.of(orgId, "owner"));

        assertThat(awaitOrgDeleted().deletedAt()).isNotNull();
    }

    @Test
    void aLaterHigherVersionActiveRecordStillAppliesBecauseTheTopicIsAuthoritative() {
        String developer = member(Role.DEVELOPER, 1);
        publisher.publish(OrgDeleted.of(orgId, "owner"));
        awaitOrgDeleted();

        applyState(developer, Role.DEVELOPER, OrgMembershipChanged.STATUS_ACTIVE, 2);

        assertMembership(developer, Role.DEVELOPER, Status.ACTIVE, 2);
        assertThat(orgs.findByOrgId(orgId))
                .get()
                .extracting(OrgProjection::status)
                .isEqualTo(OrgProjection.Status.DELETED);
    }

    @Test
    void replayingTheSameOrgDeletedIsANoOp() {
        String developer = member(Role.DEVELOPER, 1);
        OrgDeleted event = OrgDeleted.of(orgId, "owner");
        publisher.publish(event);
        OrgProjection first = awaitOrgDeleted();
        applyState(developer, Role.DEVELOPER, OrgMembershipChanged.STATUS_ACTIVE, 2);
        double duplicatesBefore = duplicates();

        publisher.publish(event);

        await().atMost(WAIT).until(() -> duplicates() > duplicatesBefore);
        assertMembership(developer, Role.DEVELOPER, Status.ACTIVE, 2);
        assertThat(orgs.findByOrgId(orgId).orElseThrow().deletedAt()).isEqualTo(first.deletedAt());
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

    private OrgProjection awaitOrgDeleted() {
        return await().atMost(WAIT)
                .until(
                        () -> orgs.findByOrgId(orgId),
                        found -> found.map(OrgProjection::status).equals(Optional.of(OrgProjection.Status.DELETED)))
                .orElseThrow();
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
                .find("build_queue." + TransactionalInbox.DUPLICATES)
                .tag(TransactionalInbox.TAG_LISTENER, OrgDeletedListener.CONSUMER)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
