package io.pallet.orgteam.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.api.PageQuery;
import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.OrgMemberRemoved;
import io.pallet.common.events.OrgMemberRoleChanged;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.member.MemberExceptions.InvalidSortException;
import io.pallet.orgteam.member.MemberExceptions.MemberNotFoundException;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.org.OrgStatus;
import io.pallet.orgteam.org.Organization;
import io.pallet.orgteam.org.OrganizationRepository;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessExceptions.InvalidRoleTransitionException;
import io.pallet.orgteam.security.AccessExceptions.LastOwnerException;
import io.pallet.orgteam.security.AccessExceptions.NotAMemberException;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import io.pallet.orgteam.security.MembershipPolicy;
import io.pallet.orgteam.security.OrgGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@UnitTest
class MemberServiceTest {

    private static final String ORG = "org-1";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private Organization organization;
    private MemberService service;

    @BeforeEach
    void setUp() {
        organization = new Organization(ORG, "Acme", "acme", "owner", NOW);
        when(organizations.lockById(ORG)).thenReturn(Optional.of(organization));
        service = new MemberService(
                new OrgGuard(organizations, memberships),
                memberships,
                new MembershipPolicy(),
                outbox,
                new OrgTeamMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Membership seed(String userId, Role role) {
        Membership member = new Membership(ORG, userId, userId + "@example.com", userId, role, NOW);
        when(memberships.findByOrgIdAndUserIdAndStatus(ORG, userId, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member));
        return member;
    }

    private List<PlatformEvent> appended() {
        ArgumentCaptor<PlatformEvent> events = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(outbox, atLeast(0)).append(events.capture());
        return events.getAllValues();
    }

    @Test
    void changingARoleUpdatesTheRowAndEmitsLowercaseNames() {
        seed("owner", Role.OWNER);
        Membership dev = seed("dev", Role.DEVELOPER);

        MemberDto result = service.changeRole(ORG, "owner", "dev", Role.ADMIN);

        assertThat(dev.getRole()).isEqualTo(Role.ADMIN);
        assertThat(result.role()).isEqualTo(Role.ADMIN);
        List<PlatformEvent> events = appended();
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOfSatisfying(OrgMemberRoleChanged.class, changed -> {
            assertThat(changed.userId()).isEqualTo("dev");
            assertThat(changed.previousRole()).isEqualTo("developer");
            assertThat(changed.newRole()).isEqualTo("admin");
        });
        assertThat(events.get(1))
                .isInstanceOfSatisfying(
                        AuditEventRecorded.class,
                        audit -> assertThat(audit.action()).isEqualTo("member.role_changed"));
    }

    @Test
    void changingToTheSameRoleWritesAndEmitsNothing() {
        seed("owner", Role.OWNER);
        seed("dev", Role.DEVELOPER);

        MemberDto result = service.changeRole(ORG, "owner", "dev", Role.DEVELOPER);

        assertThat(result.role()).isEqualTo(Role.DEVELOPER);
        verify(memberships, never()).flush();
        verifyNoInteractions(outbox);
    }

    @Test
    void theOrganizationIsLockedBeforeAnyMembershipIsRead() {
        seed("owner", Role.OWNER);
        seed("dev", Role.DEVELOPER);

        service.changeRole(ORG, "owner", "dev", Role.VIEWER);

        InOrder order = inOrder(organizations, memberships);
        order.verify(organizations).lockById(ORG);
        order.verify(memberships, atLeastOnce()).findByOrgIdAndUserIdAndStatus(any(), any(), any());
    }

    @Test
    void roleChangeRejectsOwnerTargetsAndTheOwnerRole() {
        seed("owner", Role.OWNER);
        seed("admin", Role.ADMIN);

        assertThatThrownBy(() -> service.changeRole(ORG, "owner", "owner", Role.ADMIN))
                .isInstanceOf(InvalidRoleTransitionException.class);
        assertThatThrownBy(() -> service.changeRole(ORG, "owner", "admin", Role.OWNER))
                .isInstanceOf(InvalidRoleTransitionException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void anAdminCannotChangeRoles() {
        seed("admin", Role.ADMIN);
        seed("dev", Role.DEVELOPER);

        assertThatThrownBy(() -> service.changeRole(ORG, "admin", "dev", Role.VIEWER))
                .isInstanceOf(InsufficientRoleException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void roleChangeOfAnUnknownOrRemovedMemberIsNotFound() {
        seed("owner", Role.OWNER);
        when(memberships.findByOrgIdAndUserIdAndStatus(ORG, "ghost", MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeRole(ORG, "owner", "ghost", Role.VIEWER))
                .isInstanceOf(MemberNotFoundException.class);
    }

    @Test
    void anActorRemovedWhileWaitingForTheLockIsNotAMember() {
        seed("dev", Role.DEVELOPER);
        when(memberships.findByOrgIdAndUserIdAndStatus(ORG, "stale-admin", MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(ORG, "stale-admin", "dev")).isInstanceOf(NotAMemberException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void aDeletedOrganizationIsNotFound() {
        Organization deleted = mock(Organization.class);
        when(deleted.getStatus()).thenReturn(OrgStatus.DELETED);
        when(organizations.lockById(ORG)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.remove(ORG, "a", "b")).isInstanceOf(OrgNotFoundException.class);
    }

    @Test
    void removalMarksTheMemberDropsTeamAssignmentsAndEmitsTheEvent() {
        seed("admin", Role.ADMIN);
        Membership dev = seed("dev", Role.DEVELOPER);

        service.remove(ORG, "admin", "dev");

        assertThat(dev.getStatus()).isEqualTo(MembershipStatus.REMOVED);
        assertThat(dev.getRemovedAt()).isEqualTo(NOW);
        assertThat(dev.getRemovedBy()).isEqualTo("admin");
        verify(memberships).deleteTeamAssignments(ORG, "dev");
        List<PlatformEvent> events = appended();
        assertThat(events.get(0)).isInstanceOfSatisfying(OrgMemberRemoved.class, removed -> {
            assertThat(removed.userId()).isEqualTo("dev");
            assertThat(removed.email()).isEqualTo("dev@example.com");
        });
        assertThat(events.get(1))
                .isInstanceOfSatisfying(
                        AuditEventRecorded.class,
                        audit -> assertThat(audit.action()).isEqualTo("member.removed"));
    }

    @Test
    void leavingIsAuditedAsLeft() {
        seed("dev", Role.DEVELOPER);

        service.remove(ORG, "dev", "dev");

        assertThat(appended().get(1))
                .isInstanceOfSatisfying(
                        AuditEventRecorded.class,
                        audit -> assertThat(audit.action()).isEqualTo("member.left"));
    }

    @Test
    void theOwnerCanNeitherLeaveNorBeRemoved() {
        seed("owner", Role.OWNER);

        assertThatThrownBy(() -> service.remove(ORG, "owner", "owner")).isInstanceOf(LastOwnerException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void anAdminCannotRemoveAnotherAdmin() {
        seed("admin", Role.ADMIN);
        seed("other-admin", Role.ADMIN);

        assertThatThrownBy(() -> service.remove(ORG, "admin", "other-admin"))
                .isInstanceOf(InsufficientRoleException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void transferDemotesFirstAndFlushesThenPromotesAndEmitsPromoteBeforeDemote() {
        Membership owner = seed("owner", Role.OWNER);
        Membership dev = seed("dev", Role.DEVELOPER);
        List<Role> ownerRoleAtEachFlush = new ArrayList<>();
        List<Role> targetRoleAtEachFlush = new ArrayList<>();
        doAnswer(call -> {
                    ownerRoleAtEachFlush.add(owner.getRole());
                    targetRoleAtEachFlush.add(dev.getRole());
                    return null;
                })
                .when(memberships)
                .flush();

        MemberDto result = service.transferOwnership(ORG, "owner", "dev");

        assertThat(ownerRoleAtEachFlush).first().isEqualTo(Role.ADMIN);
        assertThat(targetRoleAtEachFlush).first().isEqualTo(Role.DEVELOPER);
        assertThat(owner.getRole()).isEqualTo(Role.ADMIN);
        assertThat(dev.getRole()).isEqualTo(Role.OWNER);
        assertThat(organization.getOwnerUserId()).isEqualTo("dev");
        assertThat(result.role()).isEqualTo(Role.OWNER);

        List<PlatformEvent> events = appended();
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOfSatisfying(OrgMemberRoleChanged.class, promote -> {
            assertThat(promote.userId()).isEqualTo("dev");
            assertThat(promote.previousRole()).isEqualTo("developer");
            assertThat(promote.newRole()).isEqualTo("owner");
        });
        assertThat(events.get(1)).isInstanceOfSatisfying(OrgMemberRoleChanged.class, demote -> {
            assertThat(demote.userId()).isEqualTo("owner");
            assertThat(demote.previousRole()).isEqualTo("owner");
            assertThat(demote.newRole()).isEqualTo("admin");
        });
        assertThat(events.get(2))
                .isInstanceOfSatisfying(
                        AuditEventRecorded.class,
                        audit -> assertThat(audit.action()).isEqualTo("org.ownership_transferred"));
    }

    @Test
    void transferToSelfOrByANonOwnerIsRejectedWithoutChanges() {
        Membership owner = seed("owner", Role.OWNER);
        seed("admin", Role.ADMIN);

        assertThatThrownBy(() -> service.transferOwnership(ORG, "owner", "owner"))
                .isInstanceOf(InvalidRoleTransitionException.class);
        assertThatThrownBy(() -> service.transferOwnership(ORG, "admin", "owner"))
                .isInstanceOf(InsufficientRoleException.class);
        assertThat(owner.getRole()).isEqualTo(Role.OWNER);
        verifyNoInteractions(outbox);
    }

    @Test
    void listingRemovedMembersNeedsAnAdmin() {
        assertThatThrownBy(() -> service.list(
                        ORG, Role.DEVELOPER, new MemberFilter(MembershipStatus.REMOVED, null, null), pageQuery(null)))
                .isInstanceOf(InsufficientRoleException.class);
        verifyNoInteractions(memberships);
    }

    @Test
    void listingDefaultsToJoinedAtWithAUserIdTiebreaker() {
        when(memberships.search(any(), any(), any(), any(), any())).thenReturn(Page.empty());

        service.list(ORG, Role.VIEWER, new MemberFilter(null, null, null), pageQuery(null));

        Pageable pageable = searchedPageable();
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Order.asc("joinedAt"), Sort.Order.asc("userId")));
    }

    @Test
    void aWhitelistedSortGainsTheTiebreakerAndDisplayNameIgnoresCase() {
        when(memberships.search(any(), any(), any(), any(), any())).thenReturn(Page.empty());

        service.list(ORG, Role.VIEWER, new MemberFilter(null, null, null), pageQuery("displayName,desc"));

        Sort sort = searchedPageable().getSort();
        assertThat(sort.getOrderFor("displayName")).satisfies(order -> {
            assertThat(order.isDescending()).isTrue();
            assertThat(order.isIgnoreCase()).isTrue();
        });
        assertThat(sort.getOrderFor("userId")).isNotNull();
    }

    @Test
    void anUnlistedSortPropertyIsRejectedBeforeReachingTheRepository() {
        for (String sort : List.of("email", "userId", "status", "joinedAt;version", "password,desc")) {
            assertThatThrownBy(
                            () -> service.list(ORG, Role.VIEWER, new MemberFilter(null, null, null), pageQuery(sort)))
                    .as(sort)
                    .isInstanceOf(InvalidSortException.class);
        }
        verifyNoInteractions(memberships);
    }

    @Test
    void listingMapsThePageToDtos() {
        Membership dev = new Membership(ORG, "dev", "dev@example.com", "Dev", Role.DEVELOPER, NOW);
        when(memberships.search(any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(dev)));

        var page = service.list(ORG, Role.VIEWER, new MemberFilter(null, null, null), pageQuery(null));

        assertThat(page.content()).singleElement().satisfies(dto -> {
            assertThat(dto.userId()).isEqualTo("dev");
            assertThat(dto.status()).isEqualTo(MembershipStatus.ACTIVE);
        });
    }

    private Pageable searchedPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(memberships).search(any(), any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    private static PageQuery pageQuery(String sort) {
        return new PageQuery(null, null, sort);
    }
}
