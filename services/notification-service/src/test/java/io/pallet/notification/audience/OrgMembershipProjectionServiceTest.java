package io.pallet.notification.audience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgMemberRemoved;
import io.pallet.common.test.annotations.RepositoryTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@RepositoryTest
class OrgMembershipProjectionServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(10);
    private static final Instant T2 = T0.plusSeconds(20);

    @Autowired
    private OrgMembershipRepository repository;

    private OrgMembershipProjectionService service;
    private String orgId;

    @BeforeEach
    void setUp() {
        service = new OrgMembershipProjectionService(repository);
        orgId = "org-" + UUID.randomUUID();
    }

    private OrgMemberAdded added(String userId, Instant at) {
        return new OrgMemberAdded(UUID.randomUUID(), OrgMemberAdded.TYPE, orgId, at, userId, userId + "@example.com");
    }

    private OrgMemberRemoved removed(String userId, Instant at) {
        return new OrgMemberRemoved(
                UUID.randomUUID(), OrgMemberRemoved.TYPE, orgId, at, userId, userId + "@example.com");
    }

    private OrgMemberStatus statusOf(String userId) {
        return repository.findById(new OrgMemberId(orgId, userId)).orElseThrow().getStatus();
    }

    @Test
    void addedCreatesAnActiveMember() {
        service.apply(added("u1", T0));

        assertThat(statusOf("u1")).isEqualTo(OrgMemberStatus.ACTIVE);
    }

    @Test
    void redeliveredAddedChangesNothing() {
        OrgMemberAdded event = added("u1", T0);

        service.apply(event);
        service.apply(event);

        assertThat(repository.findByOrgIdAndStatus(orgId, OrgMemberStatus.ACTIVE))
                .hasSize(1);
    }

    @Test
    void newerRemovedOverridesAdded() {
        service.apply(added("u1", T0));
        service.apply(removed("u1", T1));

        assertThat(statusOf("u1")).isEqualTo(OrgMemberStatus.REMOVED);
    }

    @Test
    void olderAddedArrivingAfterRemovedDoesNotResurrectTheMember() {
        service.apply(removed("u1", T1));
        service.apply(added("u1", T0));

        assertThat(statusOf("u1")).isEqualTo(OrgMemberStatus.REMOVED);
    }

    @Test
    void olderAddedAfterAddedThenRemovedStaysRemoved() {
        service.apply(added("u1", T0));
        service.apply(removed("u1", T2));
        service.apply(added("u1", T1));

        assertThat(statusOf("u1")).isEqualTo(OrgMemberStatus.REMOVED);
    }

    @Test
    void olderRemovedArrivingAfterANewerAddedLeavesTheMemberActive() {
        service.apply(added("u1", T2));
        service.apply(removed("u1", T1));

        assertThat(statusOf("u1")).isEqualTo(OrgMemberStatus.ACTIVE);
    }

    @Test
    void redeliveredRemovedChangesNothing() {
        OrgMemberRemoved event = removed("u1", T1);

        service.apply(event);
        service.apply(event);

        assertThat(statusOf("u1")).isEqualTo(OrgMemberStatus.REMOVED);
    }

    @Test
    void orgDeletedRemovesEveryMemberOfThatOrgOnly() {
        String otherOrg = "org-" + UUID.randomUUID();
        service.apply(added("u1", T0));
        service.apply(added("u2", T0));
        service.apply(new OrgMemberAdded(UUID.randomUUID(), OrgMemberAdded.TYPE, otherOrg, T0, "u3", "u3@example.com"));

        service.apply(new OrgDeleted(UUID.randomUUID(), OrgDeleted.TYPE, orgId, T1, "owner"));

        assertThat(repository.findByOrgIdAndStatus(orgId, OrgMemberStatus.ACTIVE))
                .isEmpty();
        assertThat(repository.findByOrgIdAndStatus(otherOrg, OrgMemberStatus.ACTIVE))
                .hasSize(1);
    }

    @Test
    void orgDeletedOlderThanAMembershipFactLeavesThatMemberActive() {
        service.apply(added("u1", T2));

        service.apply(new OrgDeleted(UUID.randomUUID(), OrgDeleted.TYPE, orgId, T1, "owner"));

        assertThat(statusOf("u1")).isEqualTo(OrgMemberStatus.ACTIVE);
    }

    @Test
    void anEventMissingARequiredFieldIsRejected() {
        OrgMemberAdded malformed =
                new OrgMemberAdded(UUID.randomUUID(), OrgMemberAdded.TYPE, orgId, T0, null, "u1@example.com");

        assertThatThrownBy(() -> service.apply(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }
}
