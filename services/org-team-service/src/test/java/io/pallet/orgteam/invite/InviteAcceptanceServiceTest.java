package io.pallet.orgteam.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.OrgInviteRejected;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.inbox.MalformedEventException;
import io.pallet.orgteam.member.Membership;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.member.MembershipStatus;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.org.OrgStatus;
import io.pallet.orgteam.org.Organization;
import io.pallet.orgteam.org.OrganizationRepository;
import io.pallet.orgteam.outbox.OutboxWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

@UnitTest
class InviteAcceptanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(2);
    private static final String ORG_ID = "org-1";
    private static final String USER_ID = "user-9";
    private static final UUID INVITE_ID = UUID.fromString("0b6f7a52-0000-4000-8000-000000000001");
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofHours(1));

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final InviteRepository invites = mock(InviteRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private InviteAcceptanceService service;

    @BeforeEach
    void setUp() {
        OrgTeamProperties properties = mock(OrgTeamProperties.class);
        when(properties.invites())
                .thenReturn(new OrgTeamProperties.Invites(
                        Duration.ofHours(72), 50, Duration.ofMinutes(5), 4, GRACE, "http://x/{token}"));
        service = new InviteAcceptanceService(
                organizations,
                memberships,
                invites,
                outbox,
                new OrgTeamMetrics(meters),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(organizations.lockById(ORG_ID)).thenReturn(Optional.of(organization(OrgStatus.ACTIVE)));
    }

    private static Organization organization(OrgStatus status) {
        Organization organization = new Organization(ORG_ID, "Acme", "acme", "owner-1", NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(organization, "status", status);
        return organization;
    }

    private static Invite invite(Role role, InviteStatus status, Instant expiresAt) {
        Invite invite =
                new Invite(INVITE_ID, ORG_ID, "jane@example.com", role, "owner-1", NOW.minusSeconds(60), expiresAt);
        ReflectionTestUtils.setField(invite, "status", status);
        return invite;
    }

    private static OrgInviteAccepted accepted(Instant occurredAt) {
        return new OrgInviteAccepted(
                UUID.randomUUID(),
                OrgInviteAccepted.TYPE,
                ORG_ID,
                occurredAt,
                INVITE_ID.toString(),
                USER_ID,
                "Jane@Example.com",
                "Jane Doe",
                "owner");
    }

    private void givenInvite(Invite invite) {
        when(invites.findByOrgIdAndId(ORG_ID, INVITE_ID)).thenReturn(Optional.of(invite));
    }

    private List<PlatformEvent> appended() {
        ArgumentCaptor<PlatformEvent> captor = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(outbox, org.mockito.Mockito.atLeast(0)).append(captor.capture());
        return captor.getAllValues();
    }

    private OrgInviteRejected onlyRejection() {
        List<PlatformEvent> events = appended();
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOf(AuditEventRecorded.class);
        assertThat(((AuditEventRecorded) events.get(1)).action()).isEqualTo("invite.rejected");
        return (OrgInviteRejected) events.get(0);
    }

    static Stream<Arguments> rejections() {
        return Stream.of(
                Arguments.of("revoked", InviteStatus.REVOKED, EXPIRES_AT, NOW, OrgInviteRejected.REASON_REVOKED),
                Arguments.of("expired status", InviteStatus.EXPIRED, EXPIRES_AT, NOW, OrgInviteRejected.REASON_EXPIRED),
                Arguments.of(
                        "pending, one second past the grace",
                        InviteStatus.PENDING,
                        NOW.minus(GRACE).minusSeconds(1),
                        NOW,
                        OrgInviteRejected.REASON_EXPIRED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    void anAcceptThatCannotBeHonouredIsRejectedWithItsReasonAndWritesNoMembership(
            String name, InviteStatus status, Instant expiresAt, Instant occurredAt, String reason) {
        givenInvite(invite(Role.VIEWER, status, expiresAt));

        service.handle(accepted(occurredAt));

        OrgInviteRejected rejected = onlyRejection();
        assertThat(rejected.reason()).isEqualTo(reason);
        assertThat(rejected.orgId()).isEqualTo(ORG_ID);
        assertThat(rejected.inviteId()).isEqualTo(INVITE_ID.toString());
        assertThat(rejected.userId()).isEqualTo(USER_ID);
        assertThat(rejected.email()).isEqualTo("jane@example.com");
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void anUnknownInviteIsRejectedAsUnknown() {
        when(invites.findByOrgIdAndId(ORG_ID, INVITE_ID)).thenReturn(Optional.empty());

        service.handle(accepted(NOW));

        assertThat(onlyRejection().reason()).isEqualTo(OrgInviteRejected.REASON_UNKNOWN_INVITE);
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void anOrganizationThatNeverExistedIsRejectedAsUnknownInvite() {
        when(organizations.lockById(ORG_ID)).thenReturn(Optional.empty());

        service.handle(accepted(NOW));

        assertThat(onlyRejection().reason()).isEqualTo(OrgInviteRejected.REASON_UNKNOWN_INVITE);
        verify(invites, never()).findByOrgIdAndId(any(), any());
    }

    @Test
    void aDeletedOrganizationIsRejectedAsOrgDeleted() {
        when(organizations.lockById(ORG_ID)).thenReturn(Optional.of(organization(OrgStatus.DELETED)));
        givenInvite(invite(Role.VIEWER, InviteStatus.PENDING, EXPIRES_AT));

        service.handle(accepted(NOW));

        assertThat(onlyRejection().reason()).isEqualTo(OrgInviteRejected.REASON_ORG_DELETED);
        verify(memberships, never()).saveAndFlush(any());
    }

    static Stream<Arguments> honoured() {
        return Stream.of(
                Arguments.of("well before expiry", EXPIRES_AT, NOW),
                Arguments.of("exactly at expiry", NOW, NOW),
                Arguments.of("exactly at expiry plus grace", NOW.minus(GRACE), NOW),
                Arguments.of(
                        "processed long after expiry but accepted in time",
                        NOW.minus(Duration.ofHours(6)),
                        NOW.minus(Duration.ofHours(6)).minusSeconds(30)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("honoured")
    void aTimelyAcceptIsHonouredEvenWhenProcessedAfterExpiry(String name, Instant expiresAt, Instant occurredAt) {
        Invite invite = invite(Role.DEVELOPER, InviteStatus.PENDING, expiresAt);
        givenInvite(invite);

        service.handle(accepted(occurredAt));

        assertThat(invite.getStatus()).isEqualTo(InviteStatus.ACCEPTED);
        assertThat(invite.getRespondedAt()).isEqualTo(NOW);
        verify(memberships).saveAndFlush(any(Membership.class));
    }

    @Test
    void theMembershipTakesTheInvitesRoleAndEmailNotTheEventsClaim() {
        givenInvite(invite(Role.VIEWER, InviteStatus.PENDING, EXPIRES_AT));

        service.handle(accepted(NOW.minusSeconds(5)));

        ArgumentCaptor<Membership> saved = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).saveAndFlush(saved.capture());
        Membership member = saved.getValue();
        assertThat(member.getRole()).isEqualTo(Role.VIEWER);
        assertThat(member.getEmail()).isEqualTo("jane@example.com");
        assertThat(member.getDisplayName()).isEqualTo("Jane Doe");
        assertThat(member.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(member.getUserId()).isEqualTo(USER_ID);
        assertThat(member.getJoinedAt()).isEqualTo(NOW);
        assertThat(member.getProfileSyncedAt()).isEqualTo(NOW.minusSeconds(5));
    }

    @Test
    void anHonouredAcceptAppendsTheMemberAddedEventAndBothAuditRecords() {
        givenInvite(invite(Role.ADMIN, InviteStatus.PENDING, EXPIRES_AT));

        service.handle(accepted(NOW));

        List<PlatformEvent> events = appended();
        assertThat(events).hasSize(3);
        OrgMemberAdded added = (OrgMemberAdded) events.get(0);
        assertThat(added.userId()).isEqualTo(USER_ID);
        assertThat(added.email()).isEqualTo("jane@example.com");
        assertThat(events.subList(1, 3))
                .extracting(event -> ((AuditEventRecorded) event).action())
                .containsExactly("invite.accepted", "member.added");
        assertThat(meters.get("orgteam.invites.accepted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aBlankDisplayNameFallsBackToTheEmailLocalPart() {
        givenInvite(invite(Role.VIEWER, InviteStatus.PENDING, EXPIRES_AT));
        OrgInviteAccepted event = new OrgInviteAccepted(
                UUID.randomUUID(),
                OrgInviteAccepted.TYPE,
                ORG_ID,
                NOW,
                INVITE_ID.toString(),
                USER_ID,
                "jane@example.com",
                " ",
                null);

        service.handle(event);

        ArgumentCaptor<Membership> saved = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDisplayName()).isEqualTo("jane");
    }

    @Test
    void anInviteAlreadyAcceptedByAnotherUserIsRejected() {
        givenInvite(invite(Role.VIEWER, InviteStatus.ACCEPTED, EXPIRES_AT));

        service.handle(accepted(NOW));

        assertThat(onlyRejection().reason()).isEqualTo(OrgInviteRejected.REASON_ALREADY_ACCEPTED);
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void anAcceptNamingADifferentEmailThanTheInviteIsRejectedAsUnknown() {
        givenInvite(invite(Role.VIEWER, InviteStatus.PENDING, EXPIRES_AT));
        OrgInviteAccepted event = new OrgInviteAccepted(
                UUID.randomUUID(),
                OrgInviteAccepted.TYPE,
                ORG_ID,
                NOW,
                INVITE_ID.toString(),
                USER_ID,
                "someone.else@example.com",
                "Someone",
                null);

        service.handle(event);

        assertThat(onlyRejection().reason()).isEqualTo(OrgInviteRejected.REASON_UNKNOWN_INVITE);
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void anExistingMembershipForTheUserIsANoOpEvenIfTheInviteWasRevoked() {
        when(memberships.findByOrgIdAndUserId(ORG_ID, USER_ID))
                .thenReturn(Optional.of(new Membership(ORG_ID, USER_ID, "jane@example.com", "Jane", Role.VIEWER, NOW)));
        givenInvite(invite(Role.VIEWER, InviteStatus.REVOKED, EXPIRES_AT));

        service.handle(accepted(NOW));

        assertThat(appended()).isEmpty();
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void anEmailHeldByAnotherActiveMemberIsNeitherHonouredNorRejectedButFailsNonRetryably() {
        Invite invite = invite(Role.VIEWER, InviteStatus.PENDING, EXPIRES_AT);
        givenInvite(invite);
        when(memberships.existsByOrgIdAndEmailIgnoreCaseAndStatus(ORG_ID, "jane@example.com", MembershipStatus.ACTIVE))
                .thenReturn(true);

        assertThatThrownBy(() -> service.handle(accepted(NOW))).isInstanceOf(ConflictingMembershipException.class);

        assertThat(invite.getStatus()).isEqualTo(InviteStatus.PENDING);
        assertThat(appended()).isEmpty();
        verify(memberships, never()).saveAndFlush(any());
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                mutation("null orgId", e -> with(e, null, e.inviteId(), e.userId(), e.email(), e.occurredAt())),
                mutation("blank inviteId", e -> with(e, e.orgId(), " ", e.userId(), e.email(), e.occurredAt())),
                mutation("non-uuid inviteId", e -> with(e, e.orgId(), "nope", e.userId(), e.email(), e.occurredAt())),
                mutation("null userId", e -> with(e, e.orgId(), e.inviteId(), null, e.email(), e.occurredAt())),
                mutation("blank email", e -> with(e, e.orgId(), e.inviteId(), e.userId(), "", e.occurredAt())),
                mutation("null occurredAt", e -> with(e, e.orgId(), e.inviteId(), e.userId(), e.email(), null)),
                mutation(
                        "userId over 64 characters",
                        e -> with(e, e.orgId(), e.inviteId(), "u".repeat(65), e.email(), e.occurredAt())));
    }

    private static Arguments mutation(String name, UnaryOperator<OrgInviteAccepted> change) {
        return Arguments.of(name, change);
    }

    private static OrgInviteAccepted with(
            OrgInviteAccepted base, String orgId, String inviteId, String userId, String email, Instant occurredAt) {
        return new OrgInviteAccepted(
                base.eventId(),
                base.eventType(),
                orgId,
                occurredAt,
                inviteId,
                userId,
                email,
                base.displayName(),
                base.role());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformed")
    void aMalformedEventIsRejectedBeforeAnythingIsRead(String name, UnaryOperator<OrgInviteAccepted> change) {
        OrgInviteAccepted event = change.apply(accepted(NOW));

        assertThatThrownBy(() -> service.handle(event)).isInstanceOf(MalformedEventException.class);

        verify(organizations, never()).lockById(any());
        assertThat(appended()).isEmpty();
    }
}
