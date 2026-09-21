package io.pallet.orgteam.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.invite.InviteExceptions.QuotaExceededException;
import io.pallet.orgteam.member.MemberDto;
import io.pallet.orgteam.member.MemberExceptions.MemberNotFoundException;
import io.pallet.orgteam.member.Membership;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.member.MembershipStatus;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.org.OrgStatus;
import io.pallet.orgteam.org.Organization;
import io.pallet.orgteam.org.OrganizationRepository;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessExceptions.NotAMemberException;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import io.pallet.orgteam.security.OrgGuard;
import io.pallet.orgteam.team.TeamExceptions.AlreadyInTeamException;
import io.pallet.orgteam.team.TeamExceptions.SlugTakenException;
import io.pallet.orgteam.team.TeamExceptions.TeamNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@UnitTest
class TeamServiceTest {

    private static final String ORG = "org-1";
    private static final String ADMIN = "admin-1";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private TeamService service;

    @BeforeEach
    void setUp() {
        when(organizations.lockById(ORG)).thenReturn(Optional.of(new Organization(ORG, "Acme", "acme", "owner", NOW)));
        seedMember(ADMIN, Role.ADMIN);
        service = new TeamService(
                new OrgGuard(organizations, memberships),
                memberships,
                teams,
                teamMembers,
                outbox,
                new OrgTeamProperties(null, new OrgTeamProperties.Limits(2, 200), null, null, null, null, null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Membership seedMember(String userId, Role role) {
        Membership member = new Membership(ORG, userId, userId + "@example.com", userId, role, NOW);
        when(memberships.findByOrgIdAndUserIdAndStatus(ORG, userId, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member));
        return member;
    }

    private Team seedTeam() {
        Team team = new Team(UUID.randomUUID(), ORG, "Platform", "platform", NOW);
        when(teams.findByOrgIdAndId(ORG, team.getId())).thenReturn(Optional.of(team));
        return team;
    }

    private List<PlatformEvent> appended() {
        ArgumentCaptor<PlatformEvent> events = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(outbox, atLeast(0)).append(events.capture());
        return events.getAllValues();
    }

    @Test
    void createDerivesTheSlugFromTheNameAndAuditsIt() {
        TeamDto team = service.create(ORG, ADMIN, "  Platform Team ", null);

        assertThat(team.name()).isEqualTo("Platform Team");
        assertThat(team.slug()).isEqualTo("platform-team");
        assertThat(team.memberCount()).isZero();
        assertThat(appended()).singleElement().isInstanceOfSatisfying(AuditEventRecorded.class, audit -> {
            assertThat(audit.action()).isEqualTo("team.created");
            assertThat(audit.context()).containsEntry("teamId", team.id().toString());
        });
    }

    @Test
    void createKeepsAnExplicitSlug() {
        assertThat(service.create(ORG, ADMIN, "Platform", "infra").slug()).isEqualTo("infra");
    }

    @Test
    void createIsRefusedOnceTheQuotaIsReached() {
        when(teams.countByOrgId(ORG)).thenReturn(2L);

        assertThatThrownBy(() -> service.create(ORG, ADMIN, "Third", null)).isInstanceOf(QuotaExceededException.class);
        verify(teams, never()).saveAndFlush(any());
        assertThat(appended()).isEmpty();
    }

    @Test
    void createRejectsATakenSlugBeforeInserting() {
        when(teams.existsByOrgIdAndSlug(ORG, "platform")).thenReturn(true);

        assertThatThrownBy(() -> service.create(ORG, ADMIN, "Platform", null)).isInstanceOf(SlugTakenException.class);
        verify(teams, never()).saveAndFlush(any());
    }

    @Test
    void aSlugRaceLostAtTheConstraintIsStillSlugTaken() {
        when(teams.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"" + TeamService.SLUG_CONSTRAINT + "\""));

        assertThatThrownBy(() -> service.create(ORG, ADMIN, "Platform", null)).isInstanceOf(SlugTakenException.class);
        assertThat(appended()).isEmpty();
    }

    @Test
    void anUnrelatedIntegrityViolationIsNotMistakenForASlugConflict() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("something else");
        when(teams.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> service.create(ORG, ADMIN, "Platform", null)).isSameAs(other);
    }

    @Test
    void writesRecheckTheActorsRoleUnderTheLock() {
        seedMember("dev-1", Role.DEVELOPER);
        Team team = seedTeam();

        assertThatThrownBy(() -> service.create(ORG, "dev-1", "Platform", null))
                .isInstanceOf(InsufficientRoleException.class);
        assertThatThrownBy(() -> service.rename(ORG, "dev-1", team.getId(), "New"))
                .isInstanceOf(InsufficientRoleException.class);
        assertThatThrownBy(() -> service.delete(ORG, "dev-1", team.getId()))
                .isInstanceOf(InsufficientRoleException.class);
        assertThatThrownBy(() -> service.addMember(ORG, "dev-1", team.getId(), "x"))
                .isInstanceOf(InsufficientRoleException.class);
        assertThatThrownBy(() -> service.removeMember(ORG, "dev-1", team.getId(), "x"))
                .isInstanceOf(InsufficientRoleException.class);
        verifyNoInteractions(teamMembers);
        assertThat(appended()).isEmpty();
    }

    @Test
    void aRemovedActorIsNotAMember() {
        assertThatThrownBy(() -> service.create(ORG, "ghost", "Platform", null))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void aDeletedOrOrphanedOrgIsNotFound() {
        Organization deleted = new Organization("gone", "Gone", "gone", "owner", NOW);
        ReflectionTestUtils.setField(deleted, "status", OrgStatus.DELETED);
        when(organizations.lockById("gone")).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.create("gone", ADMIN, "Platform", null))
                .isInstanceOf(OrgNotFoundException.class);
        assertThatThrownBy(() -> service.create("missing", ADMIN, "Platform", null))
                .isInstanceOf(OrgNotFoundException.class);
    }

    @Test
    void renameChangesTheNameButNeverTheSlug() {
        Team team = seedTeam();

        TeamDto renamed = service.rename(ORG, ADMIN, team.getId(), " Infra ");

        assertThat(renamed.name()).isEqualTo("Infra");
        assertThat(renamed.slug()).isEqualTo("platform");
        assertThat(appended())
                .singleElement()
                .extracting(event -> ((AuditEventRecorded) event).action())
                .isEqualTo("team.renamed");
    }

    @Test
    void renamingToTheSameNameEmitsNothing() {
        Team team = seedTeam();

        service.rename(ORG, ADMIN, team.getId(), "Platform");

        assertThat(appended()).isEmpty();
    }

    @Test
    void anotherOrgsTeamIsNotFound() {
        assertThatThrownBy(() -> service.get(ORG, UUID.randomUUID())).isInstanceOf(TeamNotFoundException.class);
        assertThatThrownBy(() -> service.rename(ORG, ADMIN, UUID.randomUUID(), "x"))
                .isInstanceOf(TeamNotFoundException.class);
        assertThatThrownBy(() -> service.delete(ORG, ADMIN, UUID.randomUUID()))
                .isInstanceOf(TeamNotFoundException.class);
    }

    @Test
    void deleteRemovesOnlyTheTeamRowAndAuditsIt() {
        Team team = seedTeam();

        service.delete(ORG, ADMIN, team.getId());

        verify(teams).delete(team);
        verifyNoInteractions(teamMembers);
        assertThat(appended())
                .singleElement()
                .extracting(event -> ((AuditEventRecorded) event).action())
                .isEqualTo("team.deleted");
    }

    @Test
    void addMemberRequiresAnActiveOrgMember() {
        Team team = seedTeam();

        assertThatThrownBy(() -> service.addMember(ORG, ADMIN, team.getId(), "removed-or-foreign"))
                .isInstanceOf(MemberNotFoundException.class);
        verify(teamMembers, never()).saveAndFlush(any());
    }

    @Test
    void addMemberRejectsAnExistingAssignment() {
        Team team = seedTeam();
        seedMember("dev-1", Role.DEVELOPER);
        when(teamMembers.existsByOrgIdAndTeamIdAndUserId(ORG, team.getId(), "dev-1"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.addMember(ORG, ADMIN, team.getId(), "dev-1"))
                .isInstanceOf(AlreadyInTeamException.class);
        verify(teamMembers, never()).saveAndFlush(any());
    }

    @Test
    void addMemberStoresTheAssignmentAndAuditsIt() {
        Team team = seedTeam();
        seedMember("dev-1", Role.DEVELOPER);

        MemberDto added = service.addMember(ORG, ADMIN, team.getId(), "dev-1");

        assertThat(added.userId()).isEqualTo("dev-1");
        ArgumentCaptor<TeamMember> saved = ArgumentCaptor.forClass(TeamMember.class);
        verify(teamMembers).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(new TeamMemberId(team.getId(), "dev-1"));
        assertThat(saved.getValue().getOrgId()).isEqualTo(ORG);
        assertThat(saved.getValue().getAddedBy()).isEqualTo(ADMIN);
        assertThat(appended()).singleElement().isInstanceOfSatisfying(AuditEventRecorded.class, audit -> {
            assertThat(audit.action()).isEqualTo("team.member_added");
            assertThat(audit.context()).containsEntry("userId", "dev-1");
        });
    }

    @Test
    void removeMemberOfAnAbsentAssignmentIsNotFound() {
        Team team = seedTeam();
        when(teamMembers.deleteAssignment(ORG, team.getId(), "dev-1")).thenReturn(0);

        assertThatThrownBy(() -> service.removeMember(ORG, ADMIN, team.getId(), "dev-1"))
                .isInstanceOf(MemberNotFoundException.class);
        assertThat(appended()).isEmpty();
    }

    @Test
    void removeMemberAuditsTheRemoval() {
        Team team = seedTeam();
        when(teamMembers.deleteAssignment(ORG, team.getId(), "dev-1")).thenReturn(1);

        service.removeMember(ORG, ADMIN, team.getId(), "dev-1");

        assertThat(appended())
                .singleElement()
                .extracting(event -> ((AuditEventRecorded) event).action())
                .isEqualTo("team.member_removed");
    }
}
