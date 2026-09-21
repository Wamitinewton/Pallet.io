package io.pallet.orgteam.invite;

import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.error.TooManyRequestsException;
import io.pallet.common.events.NotificationRequested;
import io.pallet.orgteam.audit.AuditEvents;
import io.pallet.orgteam.config.ConstraintViolations;
import io.pallet.orgteam.config.InviteSigningProperties;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.invite.InviteExceptions.AlreadyAMemberException;
import io.pallet.orgteam.invite.InviteExceptions.InviteAlreadyPendingException;
import io.pallet.orgteam.invite.InviteExceptions.InviteNotFoundException;
import io.pallet.orgteam.invite.InviteExceptions.InviteNotPendingException;
import io.pallet.orgteam.invite.InviteExceptions.MemberPreviouslyRemovedException;
import io.pallet.orgteam.invite.InviteExceptions.QuotaExceededException;
import io.pallet.orgteam.member.MemberExceptions.InvalidSortException;
import io.pallet.orgteam.member.Membership;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.member.MembershipStatus;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.org.OrgStatus;
import io.pallet.orgteam.org.Organization;
import io.pallet.orgteam.org.OrganizationRepository;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.security.MembershipPolicy;
import io.pallet.orgteam.security.OrgGuard;
import io.pallet.orgteam.support.PageSorting;
import io.pallet.orgteam.token.InvalidTokenException;
import io.pallet.orgteam.token.SignedActionToken;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InviteService {

    static final String PENDING_EMAIL_INDEX = "ux_invites_pending_email";
    static final String TOKEN_PURPOSE = "invite";
    static final String NOTIFICATION_TYPE = "ORG_INVITE";
    static final String FALLBACK_INVITER_NAME = "A member of the team";

    private static final Set<String> SORTABLE = Set.of("createdAt", "expiresAt");
    private static final String TIEBREAKER = "id";
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc(TIEBREAKER));

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final InviteRepository invites;
    private final OrgGuard guard;
    private final MembershipPolicy policy;
    private final OutboxWriter outbox;
    private final OrgTeamMetrics metrics;
    private final OrgTeamProperties.Invites settings;
    private final String signingKey;
    private final Clock clock;

    InviteService(
            OrganizationRepository organizations,
            MembershipRepository memberships,
            InviteRepository invites,
            OrgGuard guard,
            MembershipPolicy policy,
            OutboxWriter outbox,
            OrgTeamMetrics metrics,
            OrgTeamProperties properties,
            InviteSigningProperties signing,
            Clock clock) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.invites = invites;
        this.guard = guard;
        this.policy = policy;
        this.outbox = outbox;
        this.metrics = metrics;
        this.settings = properties.invites();
        this.signingKey = signing.signingKey();
        this.clock = clock;
    }

    @Transactional
    public InviteDto create(String orgId, String actorUserId, String rawEmail, Role role) {
        Organization organization = guard.lockActive(orgId);
        Membership actor = guard.activeActor(orgId, actorUserId);
        policy.checkCanInvite(actor.getRole(), role);

        String email = rawEmail.strip().toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        requireInvitable(orgId, email, now);

        UUID inviteId = UUID.randomUUID();
        Invite invite = new Invite(inviteId, orgId, email, role, actorUserId, now, expiryFrom(now));
        try {
            invites.saveAndFlush(invite);
        } catch (DataIntegrityViolationException violation) {
            ConstraintViolations.requireViolationOf(violation, PENDING_EMAIL_INDEX);
            throw new InviteAlreadyPendingException();
        }

        requestDelivery(organization, actor, invite, now);
        outbox.append(AuditEvents.inviteCreated(orgId, actorUserId, inviteId.toString(), role.keycloakName()));
        metrics.inviteCreated();
        return InviteDto.of(invite, now);
    }

    @Transactional(readOnly = true)
    public PageResponse<InviteDto> list(String orgId, InviteStatus status, PageQuery pageQuery) {
        Instant now = clock.instant();
        Pageable pageable =
                PageSorting.resolve(pageQuery, DEFAULT_SORT, Sort.Order.desc(TIEBREAKER), InviteService::whitelisted);
        Page<Invite> page = status == null
                ? invites.findByOrgId(orgId, pageable)
                : switch (status) {
                    case PENDING -> invites.findByOrgIdAndStatusAndExpiresAtAfter(orgId, status, now, pageable);
                    case EXPIRED -> invites.findExpired(orgId, now, pageable);
                    case ACCEPTED, REVOKED -> invites.findByOrgIdAndStatus(orgId, status, pageable);
                };
        return PageResponse.of(page, invite -> InviteDto.of(invite, now));
    }

    @Transactional(readOnly = true)
    public InvitePreviewDto preview(String token) {
        Map<String, String> claims = SignedActionToken.verify(TOKEN_PURPOSE, token, signingKey, clock);
        String orgId = claims.get("orgId");
        UUID inviteId = inviteIdOf(claims);

        Invite invite = invites.findByOrgIdAndId(orgId, inviteId).orElseThrow(InviteNoLongerValidException::new);
        if (!invite.getEmail().equals(claims.get("email"))
                || !invite.getRole().keycloakName().equals(claims.get("role"))) {
            throw new InvalidTokenException("Token claims disagree with the invite");
        }
        if (!invite.isLive(clock.instant())) {
            throw new InviteNoLongerValidException();
        }
        Organization organization = organizations
                .findById(orgId)
                .filter(found -> found.getStatus() == OrgStatus.ACTIVE)
                .orElseThrow(InviteNoLongerValidException::new);
        String inviterName = memberships
                .findByOrgIdAndUserId(orgId, invite.getInvitedByUserId())
                .map(Membership::getDisplayName)
                .orElse(FALLBACK_INVITER_NAME);
        return InvitePreviewDto.of(organization.getName(), invite, inviterName);
    }

    @Transactional
    public InviteDto resend(String orgId, String actorUserId, UUID inviteId) {
        Organization organization = guard.lockActive(orgId);
        Membership actor = guard.activeActor(orgId, actorUserId);
        Invite invite = invites.findByOrgIdAndId(orgId, inviteId).orElseThrow(InviteNotFoundException::new);
        policy.checkCanInvite(actor.getRole(), invite.getRole());

        Instant now = clock.instant();
        if (!invite.isLive(now)) {
            throw new InviteNotPendingException();
        }
        Duration sinceLastSend = Duration.between(invite.getLastSentAt(), now);
        if (sinceLastSend.compareTo(settings.resendCooldown()) < 0) {
            throw new TooManyRequestsException(
                    "This invitation was sent recently. Try again shortly.",
                    settings.resendCooldown().minus(sinceLastSend));
        }
        if (invite.getSendCount() >= settings.maxSends()) {
            throw new QuotaExceededException("This invitation has reached its resend limit.");
        }

        invite.resend(now, expiryFrom(now));
        invites.flush();

        requestDelivery(organization, actor, invite, now);
        outbox.append(AuditEvents.inviteResent(
                orgId, actorUserId, inviteId.toString(), invite.getRole().keycloakName(), invite.getSendCount()));
        metrics.inviteResent();
        return InviteDto.of(invite, now);
    }

    @Transactional
    public void revoke(String orgId, String actorUserId, UUID inviteId) {
        guard.lockActive(orgId);
        Membership actor = guard.activeActor(orgId, actorUserId);
        Invite invite = invites.findByOrgIdAndId(orgId, inviteId).orElseThrow(InviteNotFoundException::new);
        policy.checkCanInvite(actor.getRole(), invite.getRole());

        Instant now = clock.instant();
        if (!invite.isLive(now)) {
            throw new InviteNotFoundException();
        }
        invite.revoke(now);
        outbox.append(AuditEvents.inviteRevoked(
                orgId, actorUserId, inviteId.toString(), invite.getRole().keycloakName()));
        metrics.inviteRevoked();
    }

    private void requireInvitable(String orgId, String email, Instant now) {
        List<MembershipStatus> held = memberships.findStatusesByEmail(orgId, email);
        if (held.contains(MembershipStatus.ACTIVE)) {
            throw new AlreadyAMemberException();
        }
        if (held.contains(MembershipStatus.REMOVED)) {
            throw new MemberPreviouslyRemovedException();
        }
        invites.findByOrgIdAndEmailAndStatus(orgId, email, InviteStatus.PENDING)
                .ifPresent(pending -> supersedeIfExpired(pending, now));
        if (invites.countByOrgIdAndStatusAndExpiresAtAfter(orgId, InviteStatus.PENDING, now)
                >= settings.maxPendingPerOrg()) {
            throw new QuotaExceededException("This organization has too many pending invitations.");
        }
    }

    private void supersedeIfExpired(Invite pending, Instant now) {
        if (pending.isLive(now)) {
            throw new InviteAlreadyPendingException();
        }
        pending.expire(now);
        invites.flush();
        metrics.invitesExpired(1);
    }

    private void requestDelivery(Organization organization, Membership actor, Invite invite, Instant now) {
        String dedupeKey = "invite:" + invite.getId() + ":" + invite.getSendCount();
        outbox.append(
                NotificationRequested.of(
                        organization.getOrgId(),
                        NOTIFICATION_TYPE,
                        invite.getEmail(),
                        null,
                        dedupeKey,
                        Map.of(
                                "orgName", organization.getName(),
                                "inviterName", actor.getDisplayName(),
                                "role", invite.getRole().keycloakName(),
                                "acceptUrl", acceptUrl(invite, now))),
                true);
    }

    private String acceptUrl(Invite invite, Instant now) {
        String token = SignedActionToken.issue(
                TOKEN_PURPOSE,
                Map.of(
                        "jti", Objects.requireNonNull(invite.getId()).toString(),
                        "orgId", invite.getOrgId(),
                        "email", invite.getEmail(),
                        "role", invite.getRole().keycloakName()),
                now,
                invite.getExpiresAt(),
                signingKey);
        return settings.acceptUrlTemplate().replace("{token}", URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    private static UUID inviteIdOf(Map<String, String> claims) {
        String orgId = claims.get("orgId");
        String jti = claims.get("jti");
        if (orgId == null || jti == null) {
            throw new InvalidTokenException("Token is missing its invite identifiers");
        }
        try {
            return UUID.fromString(jti);
        } catch (IllegalArgumentException notAUuid) {
            throw new InvalidTokenException("Token jti is not a UUID", notAUuid);
        }
    }

    private Instant expiryFrom(Instant now) {
        return now.plus(settings.ttl()).truncatedTo(ChronoUnit.SECONDS);
    }

    private static Sort.Order whitelisted(Sort.Order order) {
        if (!SORTABLE.contains(order.getProperty())) {
            throw new InvalidSortException(order.getProperty());
        }
        return order;
    }
}
