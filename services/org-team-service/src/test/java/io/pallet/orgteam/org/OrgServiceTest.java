package io.pallet.orgteam.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.inbox.MalformedEventException;
import io.pallet.orgteam.member.Membership;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.member.MembershipStatus;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import io.pallet.orgteam.outbox.OutboxWriter;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.dao.DataIntegrityViolationException;

@UnitTest
class OrgServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant EVENT_TIME = Instant.parse("2026-03-01T09:59:58Z");

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final OrgCountsRepository counts = mock(OrgCountsRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private OrgService service;

    @BeforeEach
    void setUp() {
        service = new OrgService(
                organizations,
                memberships,
                counts,
                outbox,
                new OrgTeamMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static Stream<Arguments> malformedEvents() {
        return Stream.of(
                mutation("blank orgId", e -> with(e, "", e.orgName(), e.slug(), e.ownerUserId(), e.ownerEmail())),
                mutation("null orgId", e -> with(e, null, e.orgName(), e.slug(), e.ownerUserId(), e.ownerEmail())),
                mutation("blank orgName", e -> with(e, e.orgId(), " ", e.slug(), e.ownerUserId(), e.ownerEmail())),
                mutation("blank slug", e -> with(e, e.orgId(), e.orgName(), "", e.ownerUserId(), e.ownerEmail())),
                mutation(
                        "uppercase slug",
                        e -> with(e, e.orgId(), e.orgName(), "Acme", e.ownerUserId(), e.ownerEmail())),
                mutation(
                        "slug with leading hyphen",
                        e -> with(e, e.orgId(), e.orgName(), "-acme", e.ownerUserId(), e.ownerEmail())),
                mutation(
                        "slug with trailing hyphen",
                        e -> with(e, e.orgId(), e.orgName(), "acme-", e.ownerUserId(), e.ownerEmail())),
                mutation(
                        "slug with underscore",
                        e -> with(e, e.orgId(), e.orgName(), "ac_me", e.ownerUserId(), e.ownerEmail())),
                mutation(
                        "slug over 63 characters",
                        e -> with(e, e.orgId(), e.orgName(), "a".repeat(64), e.ownerUserId(), e.ownerEmail())),
                mutation("blank ownerUserId", e -> with(e, e.orgId(), e.orgName(), e.slug(), null, e.ownerEmail())),
                mutation("blank ownerEmail", e -> with(e, e.orgId(), e.orgName(), e.slug(), e.ownerUserId(), "")),
                mutation(
                        "ownerEmail without at sign",
                        e -> with(e, e.orgId(), e.orgName(), e.slug(), e.ownerUserId(), "not-an-email")),
                mutation(
                        "ownerEmail without domain dot",
                        e -> with(e, e.orgId(), e.orgName(), e.slug(), e.ownerUserId(), "a@b")),
                mutation(
                        "orgName over 255 characters",
                        e -> with(e, e.orgId(), "n".repeat(256), e.slug(), e.ownerUserId(), e.ownerEmail())),
                mutation(
                        "orgId over 64 characters",
                        e -> with(e, "o".repeat(65), e.orgName(), e.slug(), e.ownerUserId(), e.ownerEmail())),
                mutation(
                        "missing occurredAt",
                        e -> new OrgProvisioned(
                                e.eventId(),
                                e.eventType(),
                                e.orgId(),
                                null,
                                e.orgName(),
                                e.slug(),
                                e.ownerUserId(),
                                e.ownerEmail(),
                                e.ownerDisplayName())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedEvents")
    void aMalformedEventIsRejectedBeforeAnyWrite(String ignored, OrgProvisioned event) {
        assertThatThrownBy(() -> service.provision(event)).isInstanceOf(MalformedEventException.class);

        verifyNoInteractions(organizations, memberships, outbox);
    }

    @Test
    void provisioningCreatesTheOrgTheOwnerMembershipAndBothOutboxEvents() {
        OrgProvisioned event = event("Ada@Example.COM", "Ada Lovelace");
        when(organizations.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

        service.provision(event);

        verify(organizations).insertIfAbsent("org-1", "Acme", "acme", "user-1", NOW);
        ArgumentCaptor<Membership> membership = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).save(membership.capture());
        assertThat(membership.getValue().getOrgId()).isEqualTo("org-1");
        assertThat(membership.getValue().getUserId()).isEqualTo("user-1");
        assertThat(membership.getValue().getRole()).isEqualTo(Role.OWNER);
        assertThat(membership.getValue().getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(membership.getValue().getEmail()).isEqualTo("ada@example.com");
        assertThat(membership.getValue().getDisplayName()).isEqualTo("Ada Lovelace");
        assertThat(membership.getValue().getProfileSyncedAt()).isEqualTo(EVENT_TIME);

        ArgumentCaptor<PlatformEvent> published = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(outbox, org.mockito.Mockito.times(2)).append(published.capture());
        OrgMemberAdded added = (OrgMemberAdded) published.getAllValues().get(0);
        assertThat(added.orgId()).isEqualTo("org-1");
        assertThat(added.userId()).isEqualTo("user-1");
        assertThat(added.email()).isEqualTo("ada@example.com");
        AuditEventRecorded audit = (AuditEventRecorded) published.getAllValues().get(1);
        assertThat(audit.action()).isEqualTo("org.created");
        assertThat(audit.actor()).isEqualTo("user-1");
        assertThat(audit.resource()).isEqualTo("org-1");
        assertThat(audit.context()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("blankDisplayNames")
    void aBlankDisplayNameFallsBackToTheEmailLocalPart(String displayName) {
        when(organizations.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

        service.provision(event("Grace.Hopper@example.com", displayName));

        ArgumentCaptor<Membership> membership = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).save(membership.capture());
        assertThat(membership.getValue().getDisplayName()).isEqualTo("grace.hopper");
    }

    static Stream<String> blankDisplayNames() {
        return Stream.of(null, "", "   ");
    }

    @Test
    void anAlreadyExistingOrgProducesNothing() {
        when(organizations.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(0);

        service.provision(event("ada@example.com", "Ada"));

        verifyNoInteractions(memberships, outbox);
    }

    @Test
    void aSlugClaimedByAnotherOrgIsANonRetryableConflict() {
        when(organizations.insertIfAbsent(any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("ux_organizations_slug"));

        assertThatThrownBy(() -> service.provision(event("ada@example.com", "Ada")))
                .isInstanceOf(OrgSlugConflictException.class);

        verifyNoInteractions(memberships, outbox);
    }

    @Test
    void anyOtherIntegrityViolationIsNotMistakenForASlugConflict() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("value too long");
        when(organizations.insertIfAbsent(any(), any(), any(), any(), any())).thenThrow(other);

        assertThatThrownBy(() -> service.provision(event("ada@example.com", "Ada")))
                .isSameAs(other);
    }

    @Test
    void renamingToTheSameNameIsANoOp() {
        Organization org = organization("Acme");
        when(organizations.findById("org-1")).thenReturn(Optional.of(org));
        when(counts.countsFor("org-1")).thenReturn(new OrgDto.Counts(1, 0, 0));

        OrgDto dto = service.rename("org-1", "user-1", "Acme");

        assertThat(dto.name()).isEqualTo("Acme");
        verifyNoInteractions(outbox);
    }

    @Test
    void renamingChangesTheNameAndAuditsTheChange() {
        Organization org = organization("Acme");
        when(organizations.findById("org-1")).thenReturn(Optional.of(org));
        when(counts.countsFor("org-1")).thenReturn(new OrgDto.Counts(1, 0, 0));

        OrgDto dto = service.rename("org-1", "user-1", "Acme Inc");

        assertThat(dto.name()).isEqualTo("Acme Inc");
        assertThat(org.getUpdatedAt()).isEqualTo(NOW);
        ArgumentCaptor<PlatformEvent> published = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(outbox).append(published.capture());
        AuditEventRecorded audit = (AuditEventRecorded) published.getValue();
        assertThat(audit.action()).isEqualTo("org.renamed");
        assertThat(audit.actor()).isEqualTo("user-1");
        assertThat(audit.context()).containsEntry("from", "Acme").containsEntry("to", "Acme Inc");
    }

    @Test
    void anUnknownOrgIsNotFoundOnGetAndRename() {
        when(organizations.findById(eq("missing"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing")).isInstanceOf(OrgNotFoundException.class);
        assertThatThrownBy(() -> service.rename("missing", "user-1", "Acme")).isInstanceOf(OrgNotFoundException.class);
        verify(outbox, never()).append(any());
    }

    @Test
    void getReturnsTheOrgWithItsCountsAndNoVersion() {
        when(organizations.findById("org-1")).thenReturn(Optional.of(organization("Acme")));
        when(counts.countsFor("org-1")).thenReturn(new OrgDto.Counts(3, 2, 1));

        OrgDto dto = service.get("org-1");

        assertThat(dto.orgId()).isEqualTo("org-1");
        assertThat(dto.slug()).isEqualTo("acme");
        assertThat(dto.ownerUserId()).isEqualTo("user-1");
        assertThat(dto.status()).isEqualTo(OrgStatus.ACTIVE);
        assertThat(dto.counts()).isEqualTo(new OrgDto.Counts(3, 2, 1));
    }

    private static Organization organization(String name) {
        return new Organization("org-1", name, "acme", "user-1", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static OrgProvisioned event(String ownerEmail, String displayName) {
        return new OrgProvisioned(
                UUID.randomUUID(),
                OrgProvisioned.TYPE,
                "org-1",
                EVENT_TIME,
                " Acme ",
                "acme",
                "user-1",
                ownerEmail,
                displayName);
    }

    private static OrgProvisioned with(
            OrgProvisioned base, String orgId, String orgName, String slug, String ownerUserId, String ownerEmail) {
        return new OrgProvisioned(
                base.eventId(),
                base.eventType(),
                orgId,
                base.occurredAt(),
                orgName,
                slug,
                ownerUserId,
                ownerEmail,
                base.ownerDisplayName());
    }

    private static Arguments mutation(String name, UnaryOperator<OrgProvisioned> mutate) {
        return Arguments.of(name, mutate.apply(event("ada@example.com", "Ada")));
    }
}
