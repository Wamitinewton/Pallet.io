package io.pallet.orgteam.org;

import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.orgteam.audit.AuditEvents;
import io.pallet.orgteam.config.ConstraintViolations;
import io.pallet.orgteam.inbox.EventPayloads;
import io.pallet.orgteam.inbox.MalformedEventException;
import io.pallet.orgteam.member.Membership;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrgService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String SLUG_INDEX = "ux_organizations_slug";
    private static final int ID_MAX = 64;
    private static final int TEXT_MAX = 255;

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final OrgCountsRepository counts;
    private final OutboxWriter outbox;
    private final OrgTeamMetrics metrics;
    private final Clock clock;

    OrgService(
            OrganizationRepository organizations,
            MembershipRepository memberships,
            OrgCountsRepository counts,
            OutboxWriter outbox,
            OrgTeamMetrics metrics,
            Clock clock) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.counts = counts;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void provision(OrgProvisioned event) {
        validate(event);
        String email = event.ownerEmail().strip().toLowerCase(Locale.ROOT);
        Instant now = clock.instant();

        int inserted;
        try {
            inserted = organizations.insertIfAbsent(
                    event.orgId(), event.orgName().strip(), event.slug(), event.ownerUserId(), now);
        } catch (DataIntegrityViolationException violation) {
            ConstraintViolations.requireViolationOf(violation, SLUG_INDEX);
            throw new OrgSlugConflictException(event.orgId(), violation);
        }
        if (inserted == 0) {
            metrics.eventDropped(MetricsCatalog.LISTENER_ORG_PROVISIONED, "already_provisioned");
            return;
        }

        Membership owner = new Membership(
                event.orgId(),
                event.ownerUserId(),
                email,
                EventPayloads.displayNameOrLocalPart(event.ownerDisplayName(), email),
                Role.OWNER,
                now);
        owner.markProfileSynced(event.occurredAt());
        memberships.save(owner);

        outbox.append(OrgMemberAdded.of(event.orgId(), event.ownerUserId(), email));
        outbox.append(AuditEvents.orgCreated(event.orgId(), event.ownerUserId()));
        metrics.memberAdded();
        metrics.eventProcessed(MetricsCatalog.LISTENER_ORG_PROVISIONED);
    }

    @Transactional(readOnly = true)
    public OrgDto get(String orgId) {
        Organization org = organizations.findById(orgId).orElseThrow(OrgNotFoundException::new);
        return OrgDto.of(org, counts.countsFor(orgId));
    }

    @Transactional
    public OrgDto rename(String orgId, String actorUserId, String newName) {
        Organization org = organizations.findById(orgId).orElseThrow(OrgNotFoundException::new);
        String previousName = org.getName();
        if (!previousName.equals(newName)) {
            org.rename(newName, clock.instant());
            outbox.append(AuditEvents.orgRenamed(orgId, actorUserId, previousName, newName));
        }
        return OrgDto.of(org, counts.countsFor(orgId));
    }

    private static void validate(OrgProvisioned event) {
        EventPayloads.requireText("OrgProvisioned", "orgId", event.orgId(), ID_MAX);
        EventPayloads.requireText("OrgProvisioned", "orgName", event.orgName(), TEXT_MAX);
        EventPayloads.requireText("OrgProvisioned", "slug", event.slug(), ID_MAX);
        EventPayloads.requireText("OrgProvisioned", "ownerUserId", event.ownerUserId(), ID_MAX);
        EventPayloads.requireText("OrgProvisioned", "ownerEmail", event.ownerEmail(), TEXT_MAX);
        if (event.occurredAt() == null) {
            throw new MalformedEventException("OrgProvisioned.occurredAt is required");
        }
        if (!SLUG.matcher(event.slug()).matches()) {
            throw new MalformedEventException("OrgProvisioned.slug is not a DNS-1123 label");
        }
        if (!EMAIL.matcher(event.ownerEmail().strip()).matches()) {
            throw new MalformedEventException("OrgProvisioned.ownerEmail is not an email address");
        }
        String displayName = event.ownerDisplayName();
        if (displayName != null && displayName.length() > TEXT_MAX) {
            throw new MalformedEventException("OrgProvisioned.ownerDisplayName is too long");
        }
    }
}
