package io.pallet.orgteam.member;

import io.pallet.common.events.UserProfileUpdated;
import io.pallet.orgteam.inbox.EventPayloads;
import io.pallet.orgteam.inbox.MalformedEventException;
import io.pallet.orgteam.observability.MetricsCatalog;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileProjectionService {

    static final String LISTENER = MetricsCatalog.LISTENER_USER_PROFILE_UPDATED;

    private static final Logger log = LoggerFactory.getLogger(ProfileProjectionService.class);
    private static final int ID_MAX = 64;
    private static final int TEXT_MAX = 255;

    private final MembershipRepository memberships;
    private final OrgTeamMetrics metrics;

    ProfileProjectionService(MembershipRepository memberships, OrgTeamMetrics metrics) {
        this.memberships = memberships;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(UserProfileUpdated event) {
        validate(event);
        String orgId = event.orgId();
        String userId = event.userId();
        String email = event.email().strip().toLowerCase(Locale.ROOT);
        String displayName = event.displayName().strip();

        Membership member = memberships.findByOrgIdAndUserId(orgId, userId).orElse(null);
        if (member == null) {
            metrics.profileNotYetProjected();
            log.info("Profile update for user {} in organization {} arrived before its membership", userId, orgId);
            throw new MembershipNotYetProjectedException(orgId, userId);
        }
        if (member.getStatus() != MembershipStatus.ACTIVE) {
            dropped("removed");
            return;
        }
        Instant syncedAt = member.getProfileSyncedAt();
        if (syncedAt != null && !event.occurredAt().isAfter(syncedAt)) {
            dropped("stale");
            return;
        }
        if (!member.getEmail().equals(email)
                && memberships.existsByOrgIdAndEmailIgnoreCaseAndStatusAndUserIdNot(
                        orgId, email, MembershipStatus.ACTIVE, userId)) {
            throw emailConflict(orgId, userId);
        }

        member.syncProfile(email, displayName, event.occurredAt());
        try {
            memberships.saveAndFlush(member);
        } catch (DataIntegrityViolationException collision) {
            throw emailConflict(orgId, userId);
        }
        metrics.eventProcessed(LISTENER);
    }

    private ProfileEmailConflictException emailConflict(String orgId, String userId) {
        log.error("Profile update for user {} in organization {} conflicts with another active member", userId, orgId);
        return new ProfileEmailConflictException(orgId, userId);
    }

    private void dropped(String reason) {
        metrics.eventDropped(LISTENER, reason);
    }

    private static void validate(UserProfileUpdated event) {
        EventPayloads.requireText("UserProfileUpdated", "orgId", event.orgId(), ID_MAX);
        EventPayloads.requireText("UserProfileUpdated", "userId", event.userId(), ID_MAX);
        EventPayloads.requireText("UserProfileUpdated", "email", event.email(), TEXT_MAX);
        EventPayloads.requireText("UserProfileUpdated", "displayName", event.displayName(), TEXT_MAX);
        if (event.occurredAt() == null) {
            throw new MalformedEventException("UserProfileUpdated.occurredAt is required");
        }
    }
}
