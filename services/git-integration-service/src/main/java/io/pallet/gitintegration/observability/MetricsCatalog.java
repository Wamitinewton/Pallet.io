package io.pallet.gitintegration.observability;

import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.outbox.OutboxMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Every meter this service registers (ARCHITECTURE.md §Observability and operations). A tag value is always a fixed
 * code, never an org, app, installation, repository, login, SHA, or delivery id.
 */
public final class MetricsCatalog {

    public static final String PREFIX = "git";

    public static final String WEBHOOKS_RECEIVED = "git.webhooks.received";
    public static final String WEBHOOK_SIGNATURE_FAILURES = "git.webhook.signature_failures";
    public static final String WEBHOOK_SIGNATURE_VERIFIED = "git.webhook.signature_verified";
    public static final String WEBHOOK_PAYLOAD_SANITIZED = "git.webhook.payload_sanitized";
    public static final String WEBHOOK_ACK_LATENCY = "git.webhook.ack_latency";
    public static final String WEBHOOK_IP_ALLOWLIST = "git.webhook.ip_allowlist";

    public static final String DELIVERIES_PENDING = "git.deliveries.pending";
    public static final String DELIVERIES_OLDEST_PENDING_AGE = "git.deliveries.oldest_pending_age_seconds";
    public static final String DELIVERIES_PARKED = "git.deliveries.parked";
    public static final String DELIVERIES_PROCESSED = "git.deliveries.processed";
    public static final String DELIVERIES_FAILURES = "git.deliveries.failures";
    public static final String PUSHES_PUBLISHED = "git.pushes.published";
    public static final String PUSHES_SKIPPED = "git.pushes.skipped";
    public static final String CHAIN_RULE_OUTCOME = "git.chain_rule.outcome";
    public static final String PUSH_TO_OUTBOX_LATENCY = "git.push.to_outbox_latency";

    public static final String REDELIVERY_REQUESTED = "git.redelivery.requested";
    public static final String REDELIVERY_FAILED = "git.redelivery.failed";
    public static final String RECONCILER_CHECKED = "git.reconciler.checked";
    public static final String RECONCILER_PUSHES_FOUND = "git.reconciler.pushes_found";
    public static final String RECONCILER_RUN_DURATION = "git.reconciler.run_duration";

    public static final String CHECKS_DESIRED = "git.checks.desired";
    public static final String CHECKS_REPORTED = "git.checks.reported";
    public static final String CHECKS_FAILURES = "git.checks.failures";
    public static final String CHECKS_DROPPED = "git.checks.dropped";

    public static final String SESSIONS_CREATED = "git.sessions.created";
    public static final String SESSIONS_ACTIVE = "git.sessions.active";
    public static final String SESSIONS_UNDECRYPTABLE = "git.sessions.undecryptable";
    public static final String AUTHORIZATION_STATE_REJECTED = "git.authorization.state_rejected";
    public static final String LINK_ACCESS_DENIED = "git.link.access_denied";
    public static final String REVERIFY_CHECKED = "git.reverify.checked";
    public static final String REVERIFY_DISCONNECTED = "git.reverify.disconnected";
    public static final String REVERIFY_OLDEST_CHECK_AGE = "git.reverify.oldest_check_age_seconds";
    public static final String REVERIFY_RUN_DURATION = "git.reverify.run_duration";
    public static final String INSTALLATIONS_UNUSED = "git.installations.unused";
    public static final String INSTALLATIONS_UNINSTALLED = "git.installations.uninstalled";
    public static final String REPOSITORY_SYNC_RUNS = "git.repository_sync.runs";

    public static final String GITHUB_CALLS = "git.github.calls";
    public static final String GITHUB_RATELIMIT_LOW_INSTALLATIONS = "git.github.ratelimit.low_installations";
    public static final String GITHUB_TOKEN_MINTS = "git.github.token_mints";
    public static final String BREAKER_STATE = "resilience4j.circuitbreaker.state";

    public static final String AUTHZ_DENIED = "git.authz.denied";
    public static final String AUTHZ_PROJECTION_LAG = "git.authz.projection_lag_seconds";
    public static final String EVENTS_FAILED = "git.events.failed";

    public static final String RETENTION_DELETED = "git.retention.deleted";
    public static final String RETENTION_NULLED = "git.retention.nulled";
    public static final String RETENTION_RUN_DURATION = "git.retention.run_duration";

    public static final String OUTBOX_PENDING = PREFIX + "." + OutboxMetrics.PENDING;
    public static final String OUTBOX_OLDEST_PENDING_AGE = PREFIX + "." + OutboxMetrics.OLDEST_PENDING_AGE;
    public static final String OUTBOX_HELD_BACK = PREFIX + "." + OutboxMetrics.HELD_BACK;
    public static final String OUTBOX_PARKED = PREFIX + "." + OutboxMetrics.PARKED;
    public static final String OUTBOX_RELAY_ACTIVE = PREFIX + "." + OutboxMetrics.RELAY_ACTIVE;
    public static final String INBOX_DUPLICATES = PREFIX + "." + TransactionalInbox.DUPLICATES;

    public static final String LISTENER_BUILD_STARTED = "check-run-build-started";
    public static final String LISTENER_BUILD_SUCCEEDED = "check-run-build-succeeded";
    public static final String LISTENER_BUILD_FAILED = "check-run-build-failed";
    public static final String LISTENER_DEPLOY_STATE = "check-run-deploy-state";
    public static final String LISTENER_MEMBERSHIP_STATE = "membership-state-projection";
    public static final String LISTENER_APP_CREATED = "app-created-projection";
    public static final String LISTENER_APP_DELETED = "app-deleted-projection";
    public static final String LISTENER_ORG_DELETED = "org-deleted-projection";

    public static final List<String> LISTENERS = List.of(
            LISTENER_BUILD_STARTED,
            LISTENER_BUILD_SUCCEEDED,
            LISTENER_BUILD_FAILED,
            LISTENER_DEPLOY_STATE,
            LISTENER_MEMBERSHIP_STATE,
            LISTENER_APP_CREATED,
            LISTENER_APP_DELETED,
            LISTENER_ORG_DELETED);

    public static final String TAG_EVENT = "event";
    public static final String TAG_RESULT = "result";
    public static final String TAG_SECRET = "secret";
    public static final String TAG_KIND = "kind";
    public static final String TAG_TRIGGER = "trigger";
    public static final String TAG_REASON = "reason";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_ENDPOINT = "endpoint";
    public static final String TAG_LISTENER = "listener";
    public static final String TAG_PURPOSE = "purpose";
    public static final String TAG_TABLE = "table";
    public static final String TAG_SWEEP = "sweep";

    /** The webhook acknowledgement SLO is p99 under 200 ms; these buckets make it answerable from the histogram. */
    public static final List<Duration> ACK_LATENCY_SLO =
            List.of(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(500));

    /** Push to Kafka is p99 under 3 s; the outbox append is the part this service decides. */
    public static final List<Duration> PUSH_TO_OUTBOX_SLO =
            List.of(Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(3));

    public static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "orgId",
            "org_id",
            "appId",
            "app_id",
            "userId",
            "user_id",
            "installationId",
            "installation_id",
            "repoId",
            "repo_id",
            "repository",
            "login",
            "sha",
            "commit",
            "deliveryId",
            "delivery_id",
            "token");

    private MetricsCatalog() {}
}
