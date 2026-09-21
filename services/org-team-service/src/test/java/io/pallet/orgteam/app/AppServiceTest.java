package io.pallet.orgteam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.events.AppCreated;
import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.app.AppExceptions.AppNotFoundException;
import io.pallet.orgteam.app.AppExceptions.InvalidRegionException;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.invite.InviteExceptions.QuotaExceededException;
import io.pallet.orgteam.member.Membership;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.member.MembershipStatus;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.org.Organization;
import io.pallet.orgteam.org.OrganizationRepository;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessExceptions.NotAMemberException;
import io.pallet.orgteam.security.OrgGuard;
import io.pallet.orgteam.team.TeamExceptions.SlugTakenException;
import io.pallet.orgteam.team.TeamExceptions.TeamNotFoundException;
import io.pallet.orgteam.team.TeamRepository;
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

@UnitTest
class AppServiceTest {

    private static final String ORG = "org-1";
    private static final String DEV = "dev-1";
    private static final String ADMIN = "admin-1";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final AppRepository apps = mock(AppRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private AppService service;

    @BeforeEach
    void setUp() {
        when(organizations.lockById(ORG)).thenReturn(Optional.of(new Organization(ORG, "Acme", "acme", "owner", NOW)));
        seedMember(DEV, Role.DEVELOPER);
        seedMember(ADMIN, Role.ADMIN);
        OrgTeamProperties properties = new OrgTeamProperties(
                null,
                new OrgTeamProperties.Limits(100, 2),
                new OrgTeamProperties.Apps(
                        new OrgTeamProperties.Apps.Regions(List.of("us-east-1"), List.of("us-central1"))),
                null,
                null,
                null,
                null);
        service = new AppService(
                new OrgGuard(organizations, memberships),
                teams,
                apps,
                new RegionCatalog(properties),
                outbox,
                new OrgTeamMetrics(new SimpleMeterRegistry()),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void seedMember(String userId, Role role) {
        when(memberships.findByOrgIdAndUserIdAndStatus(ORG, userId, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(new Membership(ORG, userId, userId + "@example.com", userId, role, NOW)));
    }

    private App seedApp(UUID teamId) {
        App app = new App(UUID.randomUUID(), ORG, teamId, "Web", "web", CloudProvider.AWS, "us-east-1", NOW);
        when(apps.findByOrgIdAndIdAndStatus(ORG, app.getId(), AppStatus.ACTIVE)).thenReturn(Optional.of(app));
        return app;
    }

    private void teamExists(UUID teamId) {
        when(teams.existsByOrgIdAndId(ORG, teamId)).thenReturn(true);
    }

    private List<PlatformEvent> appended() {
        ArgumentCaptor<PlatformEvent> events = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(outbox, atLeast(0)).append(events.capture());
        return events.getAllValues();
    }

    private static CreateAppRequest create(String name, String slug, CloudProvider provider, String region, UUID team) {
        return new CreateAppRequest(name, slug, provider, region, team);
    }

    private static UpdateAppRequest update(String name, boolean setTeam, UUID team) {
        UpdateAppRequest request = new UpdateAppRequest();
        request.setName(name);
        if (setTeam) {
            request.setTeamId(team);
        }
        return request;
    }

    @Test
    void createPersistsTheAppAndAppendsBothEvents() {
        UUID team = UUID.randomUUID();
        teamExists(team);

        AppDto app = service.create(ORG, DEV, create("  My Web App ", null, CloudProvider.AWS, "us-east-1", team));

        assertThat(app.name()).isEqualTo("My Web App");
        assertThat(app.slug()).isEqualTo("my-web-app");
        assertThat(app.cloudProvider()).isEqualTo(CloudProvider.AWS);
        assertThat(app.region()).isEqualTo("us-east-1");
        assertThat(app.teamId()).isEqualTo(team);
        List<PlatformEvent> events = appended();
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOfSatisfying(AppCreated.class, created -> {
            assertThat(created.orgId()).isEqualTo(ORG);
            assertThat(created.appId()).isEqualTo(app.id().toString());
            assertThat(created.name()).isEqualTo("My Web App");
            assertThat(created.slug()).isEqualTo("my-web-app");
            assertThat(created.teamId()).isEqualTo(team.toString());
            assertThat(created.cloudProvider()).isEqualTo("AWS");
            assertThat(created.region()).isEqualTo("us-east-1");
            assertThat(created.createdByUserId()).isEqualTo(DEV);
        });
        assertThat(events.get(1)).isInstanceOfSatisfying(AuditEventRecorded.class, audit -> {
            assertThat(audit.action()).isEqualTo("app.created");
            assertThat(audit.context()).containsEntry("region", "us-east-1");
        });
    }

    @Test
    void createWithoutATeamCarriesANullTeamId() {
        service.create(ORG, DEV, create("Web", "web", CloudProvider.GCP, "us-central1", null));

        assertThat(appended().get(0))
                .isInstanceOfSatisfying(
                        AppCreated.class,
                        created -> assertThat(created.teamId()).isNull());
    }

    @Test
    void createRejectsARegionOutsideTheProvidersAllowList() {
        assertThatThrownBy(() -> service.create(ORG, DEV, create("Web", null, CloudProvider.AWS, "us-central1", null)))
                .isInstanceOf(InvalidRegionException.class);
        verify(apps, never()).saveAndFlush(any());
        assertThat(appended()).isEmpty();
    }

    @Test
    void createRejectsATeamOutsideTheOrganization() {
        UUID foreign = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(ORG, DEV, create("Web", null, CloudProvider.AWS, "us-east-1", foreign)))
                .isInstanceOf(TeamNotFoundException.class);
        verify(apps, never()).saveAndFlush(any());
    }

    @Test
    void createIsRefusedOnceTheActiveQuotaIsReached() {
        when(apps.countByOrgIdAndStatus(ORG, AppStatus.ACTIVE)).thenReturn(2L);

        assertThatThrownBy(() -> service.create(ORG, DEV, create("Web", null, CloudProvider.AWS, "us-east-1", null)))
                .isInstanceOf(QuotaExceededException.class);
        verify(apps, never()).saveAndFlush(any());
        assertThat(appended()).isEmpty();
    }

    @Test
    void createRejectsATakenSlugAmongActiveApps() {
        when(apps.existsByOrgIdAndSlugAndStatus(ORG, "web", AppStatus.ACTIVE)).thenReturn(true);

        assertThatThrownBy(() -> service.create(ORG, DEV, create("Web", null, CloudProvider.AWS, "us-east-1", null)))
                .isInstanceOf(SlugTakenException.class);
        verify(apps, never()).saveAndFlush(any());
    }

    @Test
    void aSlugRaceLostAtTheIndexIsStillSlugTakenAndEmitsNothing() {
        when(apps.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"" + AppService.SLUG_CONSTRAINT + "\""));

        assertThatThrownBy(() -> service.create(ORG, DEV, create("Web", null, CloudProvider.AWS, "us-east-1", null)))
                .isInstanceOf(SlugTakenException.class);
        assertThat(appended()).isEmpty();
    }

    @Test
    void anUnrelatedIntegrityViolationIsNotMistakenForASlugConflict() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("something else");
        when(apps.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> service.create(ORG, DEV, create("Web", null, CloudProvider.AWS, "us-east-1", null)))
                .isSameAs(other);
    }

    @Test
    void aViewerCannotCreate() {
        seedMember("viewer-1", Role.VIEWER);

        assertThatThrownBy(() ->
                        service.create(ORG, "viewer-1", create("Web", null, CloudProvider.AWS, "us-east-1", null)))
                .isInstanceOf(InsufficientRoleException.class);
        verify(apps, never()).saveAndFlush(any());
    }

    @Test
    void aNonMemberCannotCreate() {
        assertThatThrownBy(() ->
                        service.create(ORG, "stranger", create("Web", null, CloudProvider.AWS, "us-east-1", null)))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void updateRenamesAndAudits() {
        App app = seedApp(null);

        AppDto updated = service.update(ORG, DEV, app.getId(), update(" Storefront ", false, null));

        assertThat(updated.name()).isEqualTo("Storefront");
        assertThat(updated.slug()).isEqualTo("web");
        assertThat(appended()).singleElement().isInstanceOfSatisfying(AuditEventRecorded.class, audit -> {
            assertThat(audit.action()).isEqualTo("app.updated");
        });
    }

    @Test
    void anAbsentTeamIdLeavesTheAssignmentAlone() {
        UUID team = UUID.randomUUID();
        App app = seedApp(team);

        AppDto updated = service.update(ORG, DEV, app.getId(), update("Storefront", false, null));

        assertThat(updated.teamId()).isEqualTo(team);
    }

    @Test
    void anExplicitNullTeamIdDetachesTheApp() {
        App app = seedApp(UUID.randomUUID());

        AppDto updated = service.update(ORG, DEV, app.getId(), update(null, true, null));

        assertThat(updated.teamId()).isNull();
        assertThat(appended()).hasSize(1);
        verify(teams, never()).existsByOrgIdAndId(any(), any());
    }

    @Test
    void aNonNullTeamIdMovesTheAppWhenTheTeamIsInTheOrg() {
        App app = seedApp(null);
        UUID team = UUID.randomUUID();
        teamExists(team);

        assertThat(service.update(ORG, DEV, app.getId(), update(null, true, team))
                        .teamId())
                .isEqualTo(team);
    }

    @Test
    void aNonNullTeamIdFromAnotherOrgIsRejected() {
        App app = seedApp(null);

        assertThatThrownBy(() -> service.update(ORG, DEV, app.getId(), update(null, true, UUID.randomUUID())))
                .isInstanceOf(TeamNotFoundException.class);
        assertThat(appended()).isEmpty();
    }

    @Test
    void anUnchangedUpdateWritesAndEmitsNothing() {
        UUID team = UUID.randomUUID();
        teamExists(team);
        App app = seedApp(team);

        service.update(ORG, DEV, app.getId(), update("Web", true, team));
        service.update(ORG, DEV, app.getId(), update(null, false, null));

        verify(apps, never()).flush();
        assertThat(appended()).isEmpty();
    }

    @Test
    void updateOfAnUnknownOrDeletedAppIsNotFound() {
        assertThatThrownBy(() -> service.update(ORG, DEV, UUID.randomUUID(), update("x", false, null)))
                .isInstanceOf(AppNotFoundException.class);
    }

    @Test
    void deleteSoftDeletesAndAppendsAppDeleted() {
        App app = seedApp(null);

        service.delete(ORG, ADMIN, app.getId());

        assertThat(app.getStatus()).isEqualTo(AppStatus.DELETED);
        List<PlatformEvent> events = appended();
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOfSatisfying(AppDeleted.class, deleted -> {
            assertThat(deleted.appId()).isEqualTo(app.getId().toString());
            assertThat(deleted.slug()).isEqualTo("web");
            assertThat(deleted.deletedByUserId()).isEqualTo(ADMIN);
        });
        verify(apps, never()).delete(any());
    }

    @Test
    void aDeveloperCannotDelete() {
        App app = seedApp(null);

        assertThatThrownBy(() -> service.delete(ORG, DEV, app.getId())).isInstanceOf(InsufficientRoleException.class);
        assertThat(app.getStatus()).isEqualTo(AppStatus.ACTIVE);
        assertThat(appended()).isEmpty();
    }

    @Test
    void deleteOfAnUnknownAppIsNotFound() {
        assertThatThrownBy(() -> service.delete(ORG, ADMIN, UUID.randomUUID()))
                .isInstanceOf(AppNotFoundException.class);
    }

    @Test
    void getOfAnUnknownAppIsNotFound() {
        assertThatThrownBy(() -> service.get(ORG, UUID.randomUUID())).isInstanceOf(AppNotFoundException.class);
    }
}
