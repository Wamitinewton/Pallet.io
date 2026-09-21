package io.pallet.orgteam.invite;

import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.OrgInviteRejected;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.orgteam.audit.AuditEvents;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.inbox.EventPayloads;
import io.pallet.orgteam.inbox.MalformedEventException;
import io.pallet.orgteam.member.Membership;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.member.MembershipStatus;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.org.OrgStatus;
import io.pallet.orgteam.org.Organization;
import io.pallet.orgteam.org.OrganizationRepository;
import io.pallet.orgteam.outbox.OutboxWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InviteAcceptanceService {

    private static final Logger log = LoggerFactory.getLogger(InviteAcceptanceService.class);
    private static final int ID_MAX = 64;
    private static final int TEXT_MAX = 255;

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final InviteRepository invites;
    private final OutboxWriter outbox;
    private final OrgTeamMetrics metrics;
    private final Duration acceptGrace;
    private final Clock clock;

    InviteAcceptanceService(
            OrganizationRepository organizations,
            MembershipRepository memberships,
            InviteRepository invites,
            OutboxWriter outbox,
            OrgTeamMetrics metrics,
            OrgTeamProperties properties,
            Clock clock) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.invites = invites;
        this.outbox = outbox;
        this.metrics = metrics;
        this.acceptGrace = properties.invites().acceptGrace();
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(OrgInviteAccepted event) {
        UUID inviteId = validate(event);
        String orgId = event.orgId();
        Instant now = clock.instant();

        Optional<Organization> organization = organizations.lockById(orgId);
        if (organization.isEmpty()) {
            reject(event, inviteId, OrgInviteRejected.REASON_UNKNOWN_INVITE);
            return;
        }
        if (organization.get().getStatus() != OrgStatus.ACTIVE) {
            reject(event, inviteId, OrgInviteRejected.REASON_ORG_DELETED);
            return;
        }
        if (memberships.findByOrgIdAndUserId(orgId, event.userId()).isPresent()) {
            log.info("User {} already has a membership in organization {}; accept ignored", event.userId(), orgId);
            metrics.eventDropped(MetricsCatalog.LISTENER_ORG_INVITE_ACCEPTED, "already_member");
            return;
        }

        Optional<Invite> found = invites.findByOrgIdAndId(orgId, inviteId);
        if (found.isEmpty()) {
            reject(event, inviteId, OrgInviteRejected.REASON_UNKNOWN_INVITE);
            return;
        }
        Invite invite = found.get();
        if (!invite.getEmail().equalsIgnoreCase(event.email().strip())) {
            log.warn("Accept of invite {} in organization {} names a different email than the invite", inviteId, orgId);
            reject(event, inviteId, OrgInviteRejected.REASON_UNKNOWN_INVITE);
            return;
        }
        switch (invite.getStatus()) {
            case ACCEPTED -> reject(event, inviteId, OrgInviteRejected.REASON_ALREADY_ACCEPTED);
            case REVOKED -> reject(event, inviteId, OrgInviteRejected.REASON_REVOKED);
            case EXPIRED -> reject(event, inviteId, OrgInviteRejected.REASON_EXPIRED);
            case PENDING -> {
                if (event.occurredAt().isAfter(invite.getExpiresAt().plus(acceptGrace))) {
                    reject(event, inviteId, OrgInviteRejected.REASON_EXPIRED);
                } else {
                    honour(event, invite, inviteId, now);
                }
            }
        }
    }

    private void honour(OrgInviteAccepted event, Invite invite, UUID inviteId, Instant now) {
        String orgId = invite.getOrgId();
        if (memberships.existsByOrgIdAndEmailIgnoreCaseAndStatus(orgId, invite.getEmail(), MembershipStatus.ACTIVE)) {
            throw new ConflictingMembershipException(orgId, inviteId.toString());
        }

        invite.accept(now);
        Membership member = new Membership(
                orgId,
                event.userId(),
                invite.getEmail(),
                EventPayloads.displayNameOrLocalPart(event.displayName(), invite.getEmail()),
                invite.getRole(),
                now);
        member.markProfileSynced(event.occurredAt());
        memberships.saveAndFlush(member);

        String role = invite.getRole().keycloakName();
        outbox.append(OrgMemberAdded.of(orgId, event.userId(), invite.getEmail()));
        outbox.append(AuditEvents.inviteAccepted(orgId, event.userId(), inviteId.toString(), role));
        outbox.append(AuditEvents.memberAdded(orgId, event.userId(), role));
        metrics.inviteAccepted();
        metrics.memberAdded();
        metrics.eventProcessed(MetricsCatalog.LISTENER_ORG_INVITE_ACCEPTED);
    }

    private void reject(OrgInviteAccepted event, UUID inviteId, String reason) {
        String email = event.email().strip().toLowerCase(Locale.ROOT);
        outbox.append(OrgInviteRejected.of(event.orgId(), inviteId.toString(), event.userId(), email, reason));
        outbox.append(AuditEvents.inviteRejected(event.orgId(), event.userId(), inviteId.toString(), reason));
        metrics.inviteRejected(reason);
        metrics.eventProcessed(MetricsCatalog.LISTENER_ORG_INVITE_ACCEPTED);
        log.warn("Rejected accept of invite {} in organization {}: {}", inviteId, event.orgId(), reason);
    }

    private static UUID validate(OrgInviteAccepted event) {
        EventPayloads.requireText("OrgInviteAccepted", "orgId", event.orgId(), ID_MAX);
        EventPayloads.requireText("OrgInviteAccepted", "inviteId", event.inviteId(), ID_MAX);
        EventPayloads.requireText("OrgInviteAccepted", "userId", event.userId(), ID_MAX);
        EventPayloads.requireText("OrgInviteAccepted", "email", event.email(), TEXT_MAX);
        if (event.occurredAt() == null) {
            throw new MalformedEventException("OrgInviteAccepted.occurredAt is required");
        }
        if (event.displayName() != null && event.displayName().strip().length() > TEXT_MAX) {
            throw new MalformedEventException("OrgInviteAccepted.displayName is too long");
        }
        try {
            return UUID.fromString(event.inviteId().strip());
        } catch (IllegalArgumentException notAUuid) {
            throw new MalformedEventException("OrgInviteAccepted.inviteId is not a UUID", notAUuid);
        }
    }
}
