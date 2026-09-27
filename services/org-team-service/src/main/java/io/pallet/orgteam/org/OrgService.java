package io.pallet.orgteam.org;

import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.orgteam.audit.AuditEvents;
import io.pallet.orgteam.config.ConstraintViolations;
import io.pallet.orgteam.inbox.EventPayloads;
import io.pallet.orgteam.inbox.MalformedEventException;
import io.pallet.orgteam.member.MemberExceptions.InvalidSortException;
import io.pallet.orgteam.member.Membership;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.org.OrgExceptions.EmailClaimMissingException;
import io.pallet.orgteam.org.OrgExceptions.OrgSlugTakenException;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.security.AccessContext;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import io.pallet.orgteam.support.PageSorting;
import io.pallet.orgteam.support.Slugs;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    private static final Map<String, String> SORTABLE = Map.of("kind", "kind", "name", "name");
    private static final String TIEBREAKER = "orgId";
    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.asc("kind"), Sort.Order.asc("name").ignoreCase(), Sort.Order.asc(TIEBREAKER));

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
                    event.orgId(),
                    event.orgName().strip(),
                    event.slug(),
                    event.ownerUserId(),
                    OrgKind.PERSONAL.name(),
                    now);
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

    @Transactional
    public OrgDto createTeamOrg(AccessContext caller, String rawName, String rawSlug) {
        String email = ownerEmail(caller);
        String name = rawName.strip();
        String slug = rawSlug == null ? Slugs.fromName(name) : rawSlug;
        String orgId = UUID.randomUUID().toString();
        Instant now = clock.instant();

        Organization org;
        try {
            org = organizations.saveAndFlush(new Organization(orgId, name, slug, caller.userId(), OrgKind.TEAM, now));
        } catch (DataIntegrityViolationException violation) {
            ConstraintViolations.requireViolationOf(violation, SLUG_INDEX);
            throw new OrgSlugTakenException();
        }

        Membership owner =
                new Membership(orgId, caller.userId(), email, ownerDisplayName(caller, email), Role.OWNER, now);
        owner.markProfileSynced(now);
        memberships.save(owner);

        outbox.append(OrgMemberAdded.of(orgId, caller.userId(), email));
        outbox.append(AuditEvents.orgCreated(orgId, caller.userId()));
        metrics.memberAdded();
        return OrgDto.of(org, new OrgDto.Counts(1, 0, 0));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrgSummaryDto> listMyOrgs(String userId, PageQuery pageQuery) {
        Pageable pageable =
                PageSorting.resolve(pageQuery, DEFAULT_SORT, Sort.Order.asc(TIEBREAKER), OrgService::whitelisted);
        return PageResponse.of(organizations.findMyOrgs(userId, pageable));
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

    private static String ownerEmail(AccessContext caller) {
        String email = caller.email() == null ? "" : caller.email().strip().toLowerCase(Locale.ROOT);
        if (email.length() > TEXT_MAX || !EMAIL.matcher(email).matches()) {
            throw new EmailClaimMissingException();
        }
        return email;
    }

    private static String ownerDisplayName(AccessContext caller, String email) {
        String displayName = EventPayloads.displayNameOrLocalPart(caller.displayName(), email);
        return displayName.length() > TEXT_MAX ? displayName.substring(0, TEXT_MAX) : displayName;
    }

    private static Sort.Order whitelisted(Sort.Order order) {
        String property = SORTABLE.get(order.getProperty());
        if (property == null) {
            throw new InvalidSortException(order.getProperty());
        }
        Sort.Order mapped = order.withProperty(property);
        return property.equals("name") ? mapped.ignoreCase() : mapped;
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
