package io.pallet.orgteam.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.app.AppRef;
import io.pallet.orgteam.app.AppRepository;
import io.pallet.orgteam.audit.AuditEvents;
import io.pallet.orgteam.invite.InviteRepository;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import io.pallet.orgteam.team.TeamMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@UnitTest
class OrgDeletionServiceTest {

    private static final String ORG = "org-1";
    private static final String OWNER = "owner-1";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
    private final InviteRepository invites = mock(InviteRepository.class);
    private final AppRepository apps = mock(AppRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private Organization org;
    private OrgDeletionService service;

    @BeforeEach
    void setUp() {
        org = new Organization(ORG, "Acme", "acme", OWNER, NOW.minusSeconds(3600));
        when(organizations.lockById(ORG)).thenReturn(Optional.of(org));
        service = new OrgDeletionService(
                organizations,
                memberships,
                teamMembers,
                invites,
                apps,
                outbox,
                new OrgTeamMetrics(meters),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void cascadesThroughEveryAggregateAndMarksTheOrgDeleted() {
        service.delete(ORG, OWNER, "acme");

        assertThat(org.getStatus()).isEqualTo(OrgStatus.DELETED);
        assertThat(org.getDeletedAt()).isEqualTo(NOW);
        assertThat(org.getDeletedBy()).isEqualTo(OWNER);
        verify(memberships).removeAllActive(ORG, OWNER, NOW);
        verify(teamMembers).deleteAllForOrg(ORG);
        verify(invites).revokeAllPending(ORG, NOW);
        verify(apps).deleteAllActive(ORG, NOW);
        assertThat(meters.get("orgteam.orgs.deleted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void publishesEachAppDeletionThenOrgDeletedThenAuditInThatOrder() {
        AppRef first = new AppRef(UUID.randomUUID(), "web");
        AppRef second = new AppRef(UUID.randomUUID(), "api");
        when(apps.findActiveRefs(ORG)).thenReturn(List.of(first, second));

        service.delete(ORG, OWNER, "acme");

        ArgumentCaptor<PlatformEvent> events = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(outbox, times(4)).append(events.capture());
        List<PlatformEvent> appended = events.getAllValues();
        assertThat(appended.get(0)).isInstanceOfSatisfying(AppDeleted.class, event -> {
            assertThat(event.appId()).isEqualTo(first.id().toString());
            assertThat(event.slug()).isEqualTo("web");
            assertThat(event.deletedByUserId()).isEqualTo(OWNER);
        });
        assertThat(appended.get(1))
                .isInstanceOfSatisfying(
                        AppDeleted.class, event -> assertThat(event.slug()).isEqualTo("api"));
        assertThat(appended.get(2)).isInstanceOfSatisfying(OrgDeleted.class, event -> {
            assertThat(event.orgId()).isEqualTo(ORG);
            assertThat(event.deletedByUserId()).isEqualTo(OWNER);
        });
        assertThat(appended.get(3)).isInstanceOfSatisfying(AuditEventRecorded.class, event -> {
            assertThat(event.action()).isEqualTo(AuditEvents.ORG_DELETED);
            assertThat(event.context()).containsEntry("appsDeleted", 2);
        });
    }

    @Test
    void anOrgWithoutAppsStillEmitsOrgDeletedThenAudit() {
        service.delete(ORG, OWNER, "acme");

        ArgumentCaptor<PlatformEvent> events = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(outbox, times(2)).append(events.capture());
        assertThat(events.getAllValues().get(0)).isInstanceOf(OrgDeleted.class);
        assertThat(events.getAllValues().get(1)).isInstanceOf(AuditEventRecorded.class);
    }

    @Test
    void aWrongOrMissingSlugChangesNothing() {
        assertThatThrownBy(() -> service.delete(ORG, OWNER, "acme-2"))
                .isInstanceOf(ConfirmationMismatchException.class);
        assertThatThrownBy(() -> service.delete(ORG, OWNER, null)).isInstanceOf(ConfirmationMismatchException.class);

        assertThat(org.getStatus()).isEqualTo(OrgStatus.ACTIVE);
        verifyNoInteractions(memberships, teamMembers, invites, outbox);
        verify(apps, never()).deleteAllActive(any(), any());
    }

    @Test
    void anAlreadyDeletedOrgIsNotFound() {
        org.markDeleted(OWNER, NOW.minusSeconds(60));

        assertThatThrownBy(() -> service.delete(ORG, OWNER, "acme")).isInstanceOf(OrgNotFoundException.class);
        verifyNoInteractions(memberships, teamMembers, invites, apps, outbox);
    }

    @Test
    void anUnknownOrgIsNotFound() {
        when(organizations.lockById("org-9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("org-9", OWNER, "acme")).isInstanceOf(OrgNotFoundException.class);
    }

    @Test
    void someoneWhoStoppedBeingTheOwnerBeforeTheLockIsRefused() {
        assertThatThrownBy(() -> service.delete(ORG, "former-owner", "acme"))
                .isInstanceOf(InsufficientRoleException.class);

        assertThat(org.getStatus()).isEqualTo(OrgStatus.ACTIVE);
        verifyNoInteractions(memberships, teamMembers, invites, apps, outbox);
    }
}
