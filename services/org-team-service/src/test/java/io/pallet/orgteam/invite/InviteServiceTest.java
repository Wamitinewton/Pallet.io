package io.pallet.orgteam.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.error.TooManyRequestsException;
import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.config.InviteSigningProperties;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.invite.InviteExceptions.AlreadyAMemberException;
import io.pallet.orgteam.invite.InviteExceptions.InviteAlreadyPendingException;
import io.pallet.orgteam.invite.InviteExceptions.InviteNotFoundException;
import io.pallet.orgteam.invite.InviteExceptions.InviteNotPendingException;
import io.pallet.orgteam.invite.InviteExceptions.MemberPreviouslyRemovedException;
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
import io.pallet.orgteam.security.AccessExceptions.InvalidRoleTransitionException;
import io.pallet.orgteam.security.AccessExceptions.NotAMemberException;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import io.pallet.orgteam.security.MembershipPolicy;
import io.pallet.orgteam.security.OrgGuard;
import io.pallet.orgteam.token.SignedActionToken;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@UnitTest
class InviteServiceTest {

    private static final String ORG = "org-1";
    private static final String KEY = "unit-test-signing-key-that-is-long-enough";
    private static final Instant NOW =
            Instant.now().truncatedTo(ChronoUnit.SECONDS).plusMillis(789);
    private static final Duration TTL = Duration.ofHours(72);
    private static final Duration COOLDOWN = Duration.ofMinutes(5);
    private static final String URL_PREFIX = "https://app.example/invites/";
    private static final String URL_TEMPLATE = URL_PREFIX + "{token}";

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final InviteRepository invites = mock(InviteRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private InviteService service;

    @BeforeEach
    void setUp() {
        when(organizations.lockById(ORG)).thenReturn(Optional.of(new Organization(ORG, "Acme", "acme", "owner", NOW)));
        OrgTeamProperties properties = new OrgTeamProperties(
                new OrgTeamProperties.Invites(TTL, 2, COOLDOWN, 3, Duration.ofMinutes(2), URL_TEMPLATE),
                null,
                null,
                null,
                null,
                null,
                null);
        service = new InviteService(
                organizations,
                memberships,
                invites,
                new OrgGuard(organizations, memberships),
                new MembershipPolicy(),
                outbox,
                new OrgTeamMetrics(new SimpleMeterRegistry()),
                properties,
                new InviteSigningProperties(KEY),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void actor(String userId, Role role) {
        Membership member = new Membership(ORG, userId, userId + "@example.com", "Display " + userId, role, NOW);
        when(memberships.findByOrgIdAndUserIdAndStatus(ORG, userId, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member));
    }

    private Invite invite(Role role, Instant lastSentAt, int sendCount, Instant expiresAt) {
        Invite invite = new Invite(UUID.randomUUID(), ORG, "jane@example.com", role, "owner", lastSentAt, expiresAt);
        for (int i = 1; i < sendCount; i++) {
            invite.resend(lastSentAt, expiresAt);
        }
        when(invites.findByOrgIdAndId(ORG, invite.getId())).thenReturn(Optional.of(invite));
        return invite;
    }

    private Invite liveInvite(Role role) {
        return invite(role, NOW.minus(COOLDOWN).minusSeconds(1), 1, NOW.plus(TTL));
    }

    private NotificationRequested sentNotification() {
        ArgumentCaptor<PlatformEvent> events = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(outbox).append(events.capture(), eq(true));
        return (NotificationRequested) events.getValue();
    }

    private static Map<String, String> tokenIn(NotificationRequested notification) {
        String url = (String) notification.variables().get("acceptUrl");
        String token = url.substring(URL_PREFIX.length());
        return SignedActionToken.verify(
                "invite", URLDecoder.decode(token, StandardCharsets.UTF_8), KEY, java.time.Clock.systemUTC());
    }

    @Test
    void createPersistsThePendingInviteAndRequestsSensitiveDeliveryThenAudit() {
        actor("owner", Role.OWNER);

        InviteDto dto = service.create(ORG, "owner", "  Jane@Example.COM ", Role.DEVELOPER);

        ArgumentCaptor<Invite> saved = ArgumentCaptor.forClass(Invite.class);
        verify(invites).saveAndFlush(saved.capture());
        Invite invite = saved.getValue();
        assertThat(invite.getEmail()).isEqualTo("jane@example.com");
        assertThat(invite.getStatus()).isEqualTo(InviteStatus.PENDING);
        assertThat(invite.getSendCount()).isEqualTo(1);
        assertThat(invite.getInvitedByUserId()).isEqualTo("owner");
        assertThat(dto.id()).isEqualTo(invite.getId());
        assertThat(dto.status()).isEqualTo(InviteStatus.PENDING);

        InOrder order = inOrder(outbox);
        order.verify(outbox).append(any(NotificationRequested.class), eq(true));
        order.verify(outbox).append(any(AuditEventRecorded.class));
    }

    @Test
    void theStoredExpiryIsTheTokensExpiryToTheSecond() {
        actor("owner", Role.OWNER);

        service.create(ORG, "owner", "jane@example.com", Role.DEVELOPER);

        ArgumentCaptor<Invite> saved = ArgumentCaptor.forClass(Invite.class);
        verify(invites).saveAndFlush(saved.capture());
        Invite invite = saved.getValue();
        Instant expected = NOW.plus(TTL).truncatedTo(ChronoUnit.SECONDS);
        assertThat(invite.getExpiresAt()).isEqualTo(expected);
        Map<String, String> claims = tokenIn(sentNotification());
        assertThat(claims.get("exp")).isEqualTo(String.valueOf(expected.getEpochSecond()));
        assertThat(claims.get("iat")).isEqualTo(String.valueOf(NOW.getEpochSecond()));
    }

    @Test
    void theTokenCarriesTheInviteWithLowercaseRoleAndEmail() {
        actor("owner", Role.OWNER);

        service.create(ORG, "owner", "Jane@Example.com", Role.ADMIN);

        ArgumentCaptor<Invite> saved = ArgumentCaptor.forClass(Invite.class);
        verify(invites).saveAndFlush(saved.capture());
        NotificationRequested notification = sentNotification();
        assertThat(tokenIn(notification))
                .containsEntry("purpose", "invite")
                .containsEntry("jti", saved.getValue().getId().toString())
                .containsEntry("orgId", ORG)
                .containsEntry("email", "jane@example.com")
                .containsEntry("role", "admin");
        assertThat(notification.notificationType()).isEqualTo("ORG_INVITE");
        assertThat(notification.recipient()).isEqualTo("jane@example.com");
        assertThat(notification.channel()).isNull();
        assertThat(notification.dedupeKey())
                .isEqualTo("invite:" + saved.getValue().getId() + ":1");
        assertThat(notification.variables())
                .containsEntry("orgName", "Acme")
                .containsEntry("inviterName", "Display owner")
                .containsEntry("role", "admin");
    }

    @Test
    void theAuditEventNeverCarriesTheEmailOrTheToken() {
        actor("owner", Role.OWNER);

        service.create(ORG, "owner", "jane@example.com", Role.VIEWER);

        ArgumentCaptor<AuditEventRecorded> audit = ArgumentCaptor.forClass(AuditEventRecorded.class);
        verify(outbox).append(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("invite.created");
        assertThat(audit.getValue().context()).containsOnlyKeys("inviteId", "role");
        assertThat(audit.getValue().toString())
                .doesNotContain("jane@example.com")
                .doesNotContain("eyJ");
    }

    @Test
    void anAdminMayNotInviteAnAdminAndNothingElseIsTouched() {
        actor("admin", Role.ADMIN);

        assertThatThrownBy(() -> service.create(ORG, "admin", "jane@example.com", Role.ADMIN))
                .isInstanceOf(InsufficientRoleException.class);

        verifyNoInteractions(invites);
        verify(outbox, never()).append(any(PlatformEvent.class));
    }

    @Test
    void nobodyMayInviteAnOwner() {
        actor("owner", Role.OWNER);

        assertThatThrownBy(() -> service.create(ORG, "owner", "jane@example.com", Role.OWNER))
                .isInstanceOf(InvalidRoleTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"VIEWER", "DEVELOPER"})
    void aDeveloperOrViewerMayNotInviteAnyone(Role actorRole) {
        actor("member", actorRole);

        assertThatThrownBy(() -> service.create(ORG, "member", "jane@example.com", Role.VIEWER))
                .isInstanceOf(InsufficientRoleException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"ADMIN", "DEVELOPER", "VIEWER"})
    void anOwnerMayInviteEveryRoleButOwner(Role invited) {
        actor("owner", Role.OWNER);

        InviteDto dto = service.create(ORG, "owner", "jane@example.com", invited);

        assertThat(dto.role()).isEqualTo(invited);
    }

    @Test
    void anActiveMemberWithThatEmailIsRefused() {
        actor("owner", Role.OWNER);
        when(memberships.findStatusesByEmail(ORG, "jane@example.com")).thenReturn(List.of(MembershipStatus.ACTIVE));

        assertThatThrownBy(() -> service.create(ORG, "owner", "Jane@example.com", Role.VIEWER))
                .isInstanceOf(AlreadyAMemberException.class);
    }

    @Test
    void aRemovedMemberWithThatEmailIsRefused() {
        actor("owner", Role.OWNER);
        when(memberships.findStatusesByEmail(ORG, "jane@example.com")).thenReturn(List.of(MembershipStatus.REMOVED));

        assertThatThrownBy(() -> service.create(ORG, "owner", "jane@example.com", Role.VIEWER))
                .isInstanceOf(MemberPreviouslyRemovedException.class);
    }

    @Test
    void aLivePendingInviteForTheEmailIsRefused() {
        actor("owner", Role.OWNER);
        Invite pending = liveInvite(Role.VIEWER);
        when(invites.findByOrgIdAndEmailAndStatus(ORG, "jane@example.com", InviteStatus.PENDING))
                .thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.create(ORG, "owner", "jane@example.com", Role.VIEWER))
                .isInstanceOf(InviteAlreadyPendingException.class);
        verify(invites, never()).saveAndFlush(any());
    }

    @Test
    void anExpiredButUnsweptInviteIsExpiredInlineAndDoesNotBlockTheNewOne() {
        actor("owner", Role.OWNER);
        Invite stale = invite(Role.VIEWER, NOW.minus(Duration.ofDays(4)), 1, NOW.minusSeconds(1));
        when(invites.findByOrgIdAndEmailAndStatus(ORG, "jane@example.com", InviteStatus.PENDING))
                .thenReturn(Optional.of(stale));

        service.create(ORG, "owner", "jane@example.com", Role.VIEWER);

        assertThat(stale.getStatus()).isEqualTo(InviteStatus.EXPIRED);
        InOrder order = inOrder(invites);
        order.verify(invites).flush();
        order.verify(invites).saveAndFlush(any(Invite.class));
    }

    @Test
    void reachingThePendingQuotaIsRefused() {
        actor("owner", Role.OWNER);
        when(invites.countByOrgIdAndStatusAndExpiresAtAfter(ORG, InviteStatus.PENDING, NOW))
                .thenReturn(2L);

        assertThatThrownBy(() -> service.create(ORG, "owner", "jane@example.com", Role.VIEWER))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void aCallerWhoIsNotAnActiveMemberIsRefused() {
        assertThatThrownBy(() -> service.create(ORG, "stranger", "jane@example.com", Role.VIEWER))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void aMissingOrganizationIsNotFound() {
        when(organizations.lockById("org-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("org-x", "owner", "jane@example.com", Role.VIEWER))
                .isInstanceOf(OrgNotFoundException.class);
    }

    @Test
    void resendKeepsTheJtiExtendsTheExpiryAndBumpsTheDedupeKey() {
        actor("owner", Role.OWNER);
        Invite invite = liveInvite(Role.DEVELOPER);

        InviteDto dto = service.resend(ORG, "owner", invite.getId());

        assertThat(invite.getSendCount()).isEqualTo(2);
        assertThat(invite.getLastSentAt()).isEqualTo(NOW);
        assertThat(invite.getExpiresAt()).isEqualTo(NOW.plus(TTL).truncatedTo(ChronoUnit.SECONDS));
        assertThat(dto.sendCount()).isEqualTo(2);
        NotificationRequested notification = sentNotification();
        assertThat(notification.dedupeKey()).isEqualTo("invite:" + invite.getId() + ":2");
        assertThat(tokenIn(notification)).containsEntry("jti", invite.getId().toString());
    }

    @Test
    void resendWithinTheCooldownIsRateLimitedWithRetryAfter() {
        actor("owner", Role.OWNER);
        Invite invite = invite(Role.DEVELOPER, NOW.minusSeconds(60), 1, NOW.plus(TTL));

        assertThatThrownBy(() -> service.resend(ORG, "owner", invite.getId()))
                .isInstanceOfSatisfying(
                        TooManyRequestsException.class,
                        e -> assertThat(e.getMeta()).containsEntry("retryAfter", 240L));
        verify(outbox, never()).append(any(PlatformEvent.class), anyBoolean());
    }

    @Test
    void resendBeyondTheSendCapIsRefused() {
        actor("owner", Role.OWNER);
        Invite invite = invite(Role.DEVELOPER, NOW.minus(COOLDOWN).minusSeconds(1), 3, NOW.plus(TTL));

        assertThatThrownBy(() -> service.resend(ORG, "owner", invite.getId()))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void anAdminMayNotResendOrRevokeAnAdminInvite() {
        actor("admin", Role.ADMIN);
        Invite invite = liveInvite(Role.ADMIN);

        assertThatThrownBy(() -> service.resend(ORG, "admin", invite.getId()))
                .isInstanceOf(InsufficientRoleException.class);
        assertThatThrownBy(() -> service.revoke(ORG, "admin", invite.getId()))
                .isInstanceOf(InsufficientRoleException.class);
    }

    @Test
    void resendOfAnExpiredInviteIsAConflictAndOfAnUnknownOneIsNotFound() {
        actor("owner", Role.OWNER);
        Invite expired = invite(Role.VIEWER, NOW.minus(Duration.ofDays(4)), 1, NOW.minusSeconds(1));

        assertThatThrownBy(() -> service.resend(ORG, "owner", expired.getId()))
                .isInstanceOf(InviteNotPendingException.class);
        assertThatThrownBy(() -> service.resend(ORG, "owner", UUID.randomUUID()))
                .isInstanceOf(InviteNotFoundException.class);
    }

    @Test
    void revokeMarksThePendingInviteRevokedAndAudits() {
        actor("owner", Role.OWNER);
        Invite invite = liveInvite(Role.VIEWER);

        service.revoke(ORG, "owner", invite.getId());

        assertThat(invite.getStatus()).isEqualTo(InviteStatus.REVOKED);
        assertThat(invite.getRespondedAt()).isEqualTo(NOW);
        ArgumentCaptor<AuditEventRecorded> audit = ArgumentCaptor.forClass(AuditEventRecorded.class);
        verify(outbox).append(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("invite.revoked");
        verify(outbox, never()).append(any(NotificationRequested.class), anyBoolean());
    }

    @Test
    void revokeOfATerminalOrUnknownInviteIsNotFound() {
        actor("owner", Role.OWNER);
        Invite revoked = liveInvite(Role.VIEWER);
        revoked.revoke(NOW);
        Invite expired = invite(Role.VIEWER, NOW.minus(Duration.ofDays(4)), 1, NOW.minusSeconds(1));

        assertThatThrownBy(() -> service.revoke(ORG, "owner", revoked.getId()))
                .isInstanceOf(InviteNotFoundException.class);
        assertThatThrownBy(() -> service.revoke(ORG, "owner", expired.getId()))
                .isInstanceOf(InviteNotFoundException.class);
        assertThatThrownBy(() -> service.revoke(ORG, "owner", UUID.randomUUID()))
                .isInstanceOf(InviteNotFoundException.class);
    }
}
