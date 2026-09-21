package io.pallet.orgteam.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.events.UserProfileUpdated;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.inbox.MalformedEventException;
import io.pallet.orgteam.inbox.NonRetryableEventException;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

@UnitTest
class ProfileProjectionServiceTest {

    private static final String ORG_ID = "org-1";
    private static final String USER_ID = "user-1";
    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);

    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ProfileProjectionService service;
    private Membership member;

    @BeforeEach
    void setUp() {
        service = new ProfileProjectionService(memberships, new OrgTeamMetrics(meters));
        member = new Membership(ORG_ID, USER_ID, "jane@example.com", "Jane", Role.DEVELOPER, T0.minusSeconds(600));
        member.markProfileSynced(T0);
        when(memberships.findByOrgIdAndUserId(ORG_ID, USER_ID)).thenReturn(Optional.of(member));
    }

    private static UserProfileUpdated event(Instant at, String email, String displayName) {
        return new UserProfileUpdated(
                UUID.randomUUID(), UserProfileUpdated.TYPE, ORG_ID, at, USER_ID, email, displayName);
    }

    @Test
    void aMissingMembershipIsARetryableFailureNotANonRetryableOne() {
        when(memberships.findByOrgIdAndUserId(ORG_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(event(T1, "jane@example.com", "Jane B")))
                .isInstanceOf(MembershipNotYetProjectedException.class)
                .isNotInstanceOf(NonRetryableEventException.class);
        assertThat(meters.counter("orgteam.profile.not_yet_projected").count()).isEqualTo(1.0);
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void aRemovedMemberIsLeftUntouched() {
        member.remove(T0, "owner");

        service.apply(event(T1, "new@example.com", "New Name"));

        assertThat(member.getDisplayName()).isEqualTo("Jane");
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void anOlderEventDoesNotRegressTheProjection() {
        service.apply(event(T0.minusSeconds(1), "old@example.com", "Old"));

        assertThat(member.getDisplayName()).isEqualTo("Jane");
        assertThat(member.getEmail()).isEqualTo("jane@example.com");
        assertThat(member.getProfileSyncedAt()).isEqualTo(T0);
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void anEventAtTheSameInstantIsANoOp() {
        service.apply(event(T0, "same@example.com", "Same"));

        assertThat(member.getDisplayName()).isEqualTo("Jane");
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void aNewerEventIsAppliedAndAdvancesTheTimestamp() {
        service.apply(event(T1, "jane@example.com", "  Jane Bloggs "));

        assertThat(member.getDisplayName()).isEqualTo("Jane Bloggs");
        assertThat(member.getProfileSyncedAt()).isEqualTo(T1);
        verify(memberships).saveAndFlush(member);
    }

    @Test
    void aMembershipNeverSyncedAcceptsTheFirstEvent() {
        Membership fresh = new Membership(ORG_ID, USER_ID, "jane@example.com", "Jane", Role.VIEWER, T0);
        when(memberships.findByOrgIdAndUserId(ORG_ID, USER_ID)).thenReturn(Optional.of(fresh));

        service.apply(event(T0.minusSeconds(5), "jane@example.com", "Jane B"));

        assertThat(fresh.getDisplayName()).isEqualTo("Jane B");
    }

    @Test
    void theEmailIsLowercased() {
        when(memberships.existsByOrgIdAndEmailIgnoreCaseAndStatusAndUserIdNot(
                        ORG_ID, "jane.new@example.com", MembershipStatus.ACTIVE, USER_ID))
                .thenReturn(false);

        service.apply(event(T1, "  Jane.New@Example.COM ", "Jane"));

        assertThat(member.getEmail()).isEqualTo("jane.new@example.com");
    }

    @Test
    void anEmailHeldByAnotherActiveMemberIsNonRetryableAndNothingIsWritten() {
        when(memberships.existsByOrgIdAndEmailIgnoreCaseAndStatusAndUserIdNot(
                        ORG_ID, "taken@example.com", MembershipStatus.ACTIVE, USER_ID))
                .thenReturn(true);

        assertThatThrownBy(() -> service.apply(event(T1, "taken@example.com", "Jane")))
                .isInstanceOf(NonRetryableEventException.class);
        assertThat(member.getEmail()).isEqualTo("jane@example.com");
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void aConstraintViolationOnFlushIsNonRetryable() {
        doThrow(new DataIntegrityViolationException("ux_memberships_active_email"))
                .when(memberships)
                .saveAndFlush(any());

        assertThatThrownBy(() -> service.apply(event(T1, "other@example.com", "Jane")))
                .isInstanceOf(NonRetryableEventException.class);
    }

    @Test
    void anUnchangedEmailSkipsTheCollisionCheck() {
        service.apply(event(T1, "JANE@example.com", "Jane B"));

        verify(memberships, never())
                .existsByOrgIdAndEmailIgnoreCaseAndStatusAndUserIdNot(anyString(), anyString(), any(), anyString());
    }

    @Test
    void malformedPayloadsAreNonRetryable() {
        assertMalformed(event(T1, "jane@example.com", " "));
        assertMalformed(event(T1, null, "Jane"));
        assertMalformed(event(null, "jane@example.com", "Jane"));
        assertMalformed(event(T1, "jane@example.com", "x".repeat(256)));
        UserProfileUpdated noUser = event(T1, "jane@example.com", "Jane");
        assertMalformed(new UserProfileUpdated(
                noUser.eventId(), noUser.eventType(), ORG_ID, T1, "", noUser.email(), noUser.displayName()));
        assertMalformed(new UserProfileUpdated(
                noUser.eventId(), noUser.eventType(), null, T1, USER_ID, noUser.email(), noUser.displayName()));
    }

    private void assertMalformed(UserProfileUpdated event) {
        assertThatThrownBy(() -> service.apply(event)).isInstanceOf(MalformedEventException.class);
        verify(memberships, never()).saveAndFlush(any());
    }
}
