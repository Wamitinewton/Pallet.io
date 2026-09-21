package io.pallet.orgteam.observability;

import java.util.List;
import java.util.Set;

public final class MetricsCatalog {

    public static final String OUTBOX_PENDING = "orgteam.outbox.pending";
    public static final String OUTBOX_OLDEST_PENDING_AGE = "orgteam.outbox.oldest_pending_age_seconds";
    public static final String OUTBOX_PARKED = "orgteam.outbox.parked";
    public static final String OUTBOX_PUBLISHED = "orgteam.outbox.published";
    public static final String OUTBOX_PUBLISH_FAILURES = "orgteam.outbox.publish_failures";
    public static final String OUTBOX_RELAY_ACTIVE = "orgteam.outbox.relay.active";

    public static final String EVENTS_PROCESSED = "orgteam.events.processed";
    public static final String EVENTS_DROPPED = "orgteam.events.dropped";
    public static final String EVENTS_FAILED = "orgteam.events.failed";
    public static final String INBOX_DUPLICATES = "orgteam.inbox.duplicates";
    public static final String PROFILE_NOT_YET_PROJECTED = "orgteam.profile.not_yet_projected";

    public static final String INVITES_CREATED = "orgteam.invites.created";
    public static final String INVITES_RESENT = "orgteam.invites.resent";
    public static final String INVITES_REVOKED = "orgteam.invites.revoked";
    public static final String INVITES_ACCEPTED = "orgteam.invites.accepted";
    public static final String INVITES_REJECTED = "orgteam.invites.rejected";
    public static final String INVITES_EXPIRED = "orgteam.invites.expired";

    public static final String MEMBERS_ADDED = "orgteam.members.added";
    public static final String MEMBERS_REMOVED = "orgteam.members.removed";
    public static final String MEMBERS_ROLE_CHANGED = "orgteam.members.role_changed";

    public static final String APPS_CREATED = "orgteam.apps.created";
    public static final String ORGS_DELETED = "orgteam.orgs.deleted";

    public static final String AUTHZ_DENIED = "orgteam.authz.denied";
    public static final String AUTHZ_TOKEN_ROLE_DRIFT = "orgteam.authz.token_role_drift";

    public static final String SWEEP_ROWS = "orgteam.sweep.rows";
    public static final String SWEEP_DURATION = "orgteam.sweep.duration";
    public static final String SWEEP_FAILURES = "orgteam.sweep.failures";

    public static final String LISTENER_ORG_PROVISIONED = "org-provisioned";
    public static final String LISTENER_ORG_INVITE_ACCEPTED = "org-invite-accepted";
    public static final String LISTENER_USER_PROFILE_UPDATED = "user-profile-updated";

    public static final List<String> LISTENERS =
            List.of(LISTENER_ORG_PROVISIONED, LISTENER_ORG_INVITE_ACCEPTED, LISTENER_USER_PROFILE_UPDATED);

    public static final String TAG_LISTENER = "listener";
    public static final String TAG_REASON = "reason";
    public static final String TAG_KIND = "kind";
    public static final String TAG_SWEEP = "sweep";
    public static final String TAG_EVENT_TYPE = "eventType";

    public static final String KIND_BROKER = "broker";
    public static final String KIND_ROW = "row";

    public static final String DENIED_ORG_MISMATCH = "org_mismatch";
    public static final String DENIED_ORG_NOT_FOUND = "org_not_found";
    public static final String DENIED_NOT_A_MEMBER = "not_a_member";
    public static final String DENIED_INSUFFICIENT_ROLE = "insufficient_role";
    public static final String DENIED_REAUTHENTICATION = "reauthentication_required";

    public static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "orgId", "org_id", "userId", "user_id", "email", "inviteId", "invite_id", "appId", "app_id", "token");

    private MetricsCatalog() {}
}
