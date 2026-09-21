# org-team-service — Runbook

Operator procedures for the failure modes in [ARCHITECTURE.md](ARCHITECTURE.md#consistency-and-failure-modes).
Each one is detect → diagnose → fix → verify. SQL runs against the `pallet` database, schema `org_team`; Kafka
commands assume the local stack (`pallet-kafka`), so swap the bootstrap server for a deployed cluster.

Alert rules that page into these procedures live in
[`deploy/local/prometheus/rules/org-team-service.rules.yml`](../../deploy/local/prometheus/rules/org-team-service.rules.yml);
the dashboard is
[`deploy/local/grafana/dashboards/org-team-service.json`](../../deploy/local/grafana/dashboards/org-team-service.json).

## Reading the outbox

```sql
SELECT status, count(*), min(created_at) AS oldest
FROM org_team.outbox_events
WHERE status <> 'PUBLISHED'
GROUP BY status;
```

`PENDING` rows are waiting for the relay, `PARKED` rows exhausted `pallet.orgteam.outbox.max-attempts` and are holding
their organization's later events behind them. `GET /actuator/health` shows the same numbers under `outbox` and never
turns readiness `DOWN`: a lagging outbox must alert, not restart the pod.

---

## 1. Outbox stalled

**Detect**: `OrgTeamOutboxStalled` (warn above 60 s, page above 300 s) on `orgteam_outbox_oldest_pending_age_seconds`.
Member removals and role changes are not reaching `identity-service` while this fires, so a removed person's Keycloak
account stays enabled.

**Diagnose**, in this order:

1. Is Kafka reachable? Relay logs say so directly:
   - `Broker unavailable, relay backing off PT4S: ...` is the broker. The counter
     `orgteam_outbox_publish_failures_total{kind="broker"}` climbs and no row is charged an attempt.
   - `Outbox row failed id=... attempts=n error=...` or `Outbox row parked ...` is a row fault, counted under
     `kind="row"`; other organizations keep publishing.
2. Is any relay running? `rate(orgteam_outbox_published_total[1m])` per instance should be above zero while
   `orgteam_outbox_pending` is. The lock is transaction-scoped, so `orgteam_outbox_relay_active` reports each
   instance's most recent lock attempt and can read `1` on more than one replica between polls. Trust the
   published rate, not the gauge, to see which replica is relaying; the advisory lock is what guarantees a single
   publisher.
3. Is Postgres up? The relay cannot poll without it; readiness `db` will also be `DOWN`.
4. Is a write transaction held open? `orgteam_outbox_held_back` above 0 (`OrgTeamOutboxHeldBack`) means the relay is
   withholding rows until every older transaction in the database finishes (ADR-0017). Find it with
   `SELECT pid, state, xact_start, query FROM pg_stat_activity WHERE backend_xid IS NOT NULL OR state = 'idle in transaction' ORDER BY xact_start;`
   and end the transaction (or fix the caller); the backlog then drains by itself.
5. Is one organization blocking? `PARKED` rows leave the global gauge flat while that organization's events wait:
   go to procedure 2.

**Fix**: restore the broker or database, or end the long-running transaction. The relay drains in `id` order by itself, with backoff capped at
`pallet.orgteam.outbox.broker-backoff-max`. Do not restart pods for this.

**Verify**: `orgteam_outbox_oldest_pending_age_seconds` returns to 0 and the alert resolves.

## 2. Parked outbox row

**Detect**: `OrgTeamOutboxParked` (`orgteam_outbox_parked > 0`) and an `ERROR Outbox row parked id=... eventId=...` log
line.

**Diagnose**:

```sql
SELECT id, event_id, org_id, event_type, attempts, last_error, created_at
FROM org_team.outbox_events
WHERE status = 'PARKED'
ORDER BY id;
```

`last_error` holds exception types only, never payload fragments. `UnknownEventTypeException` means a row was written by
a version whose event type this build does not know; a serializer or `RecordTooLarge` error means the payload itself is
the problem. Read the payload only if you need it, and treat it as personal data:

```sql
SELECT payload FROM org_team.outbox_events WHERE id = :id AND NOT sensitive;
```

Rows flagged `sensitive` carry an invite token until they publish, so do not print them into a shared channel.

**Fix**: correct the cause (deploy the version that knows the type, or repair the payload), then requeue:

```sql
UPDATE org_team.outbox_events
SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL
WHERE id = :id AND status = 'PARKED';
```

The organization's later events were queued behind this row and will publish immediately after it, in order. If the
row can never be published, delete it only after checking that no consumer depends on it: for membership events, a
deleted `OrgMemberRemoved` means the account must be disabled by hand (procedure 4).

**Verify**: `orgteam_outbox_parked` returns to 0 and the organization's `PENDING` rows drain.

## 3. Dead-letter topic is not empty

**Detect**: `OrgTeamDeadLetter` (`messaging_dead_letter_total` increasing for one of the topics below).

| DLT | Cause |
|---|---|
| `org.provisioned.DLT` | Malformed event, or the slug is already claimed by another organization (`OrgSlugConflictException`). |
| `org.invite.accepted.DLT` | Malformed event, or the invite's email is already held by another active member (`ConflictingMembershipException`). |
| `user.profile.updated.DLT` | The membership never appeared within the retry window (`MembershipNotYetProjectedException`), or the new email collides with another active member (`ProfileEmailConflictException`). |

**Diagnose**: the shared DLT monitor logs `Dead-letter <topic>-<partition>@<offset> eventType=... eventId=...
cause=...`. To read the record:

```bash
docker exec pallet-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic org.invite.accepted.DLT \
  --from-beginning --max-messages 5 --property print.headers=true --property print.key=true
```

**Fix**: correct the cause first (resolve the slug or email collision, or wait for the missing membership). A failed
delivery rolled back its inbox claim, so replaying the same payload is processed as a first delivery:

```bash
echo '<orgId>:<original payload json>' | docker exec -i pallet-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic org.invite.accepted \
  --property parse.key=true --property key.separator=:
```

Key the record by `orgId` so per-organization ordering holds. A malformed payload cannot be replayed; the event
producer needs fixing instead. Never replay a `user.profile.updated` record older than the membership's
`profile_synced_at`: it is dropped as stale by design.

**Verify**: the effect row exists (`organizations`, `memberships`) and `orgteam_events_processed_total{listener=...}`
moved by one.

## 4. Verify a removal reached Keycloak

Use this when someone reports that a removed member can still sign in.

1. Outbox row published:
   ```sql
   SELECT status, published_at FROM org_team.outbox_events
   WHERE org_id = :orgId AND event_type = 'org.member.removed' AND payload->>'userId' = :userId;
   ```
   `PENDING` or `PARKED` means procedures 1 or 2.
2. Event on the bus:
   ```bash
   docker exec pallet-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
     --topic org.member.removed --from-beginning --property print.key=true --timeout-ms 5000 | grep <orgId>
   ```
3. `identity-service` applied it: `identity_membership_sync_processed_total` incremented (`..._noop_total` means the
   account was already disabled), and `org.member.removed.DLT` is empty.
4. Keycloak user shows `enabled=false` (admin console, realm `pallet`).

This service already denies the person from step 1's commit onward, because authorization reads the local
membership row. Other services honour the token until it expires or its session is revoked (ADR-0015), which is up to
the access-token lifetime after step 4.

## 5. An `OrgInviteRejected` fired

**Meaning**: `identity-service` created an account for an invite this service could not honour (revoked, expired,
unknown, already accepted, or the organization was deleted). No membership was created; the event tells
`identity-service` to disable the account.

**Detect**: `orgteam_invites_rejected_total{reason=...}` increased.

**Diagnose**:

```sql
SELECT payload->>'reason' AS reason, payload->>'inviteId' AS invite_id, created_at, status
FROM org_team.outbox_events
WHERE org_id = :orgId AND event_type = 'org.invite.rejected'
ORDER BY id DESC;
```

`REVOKED` right after a revoke is the expected race (the invitee accepted before the revoke landed). `EXPIRED` outside
`pallet.orgteam.invites.accept-grace` or repeated `UNKNOWN_INVITE` for one organization points at abuse or a broken
accept page.

**Verify the account was disabled**: the outbox row is `PUBLISHED`, `identity_membership_sync_processed_total` moved,
and the Keycloak user for `payload->>'userId'` shows `enabled=false`. If it did not, that is procedure 4 with the
`org.invite.rejected` topic.

## 6. Rotate the invite signing key

**Limitation**: the HMAC key `PALLET_INVITES_SIGNING_KEY` is one symmetric secret shared by this service and
`identity-service`, and each side accepts exactly one key. There is no overlap window. Every invite link issued under
the old key stops verifying the moment either side restarts with the new one. A dual-key (`kid`) verify on the
`identity-service` side is the open question in ARCHITECTURE.md and is not built.

**Procedure**:

1. Check what is in flight:
   ```sql
   SELECT count(*) FROM org_team.invites WHERE status = 'PENDING' AND expires_at > now();
   ```
   For a planned rotation, either wait for the count to drain (links live `pallet.orgteam.invites.ttl`, 72 h by
   default) or accept that these links break.
2. Generate a key of at least 32 bytes; the service refuses to start on a shorter one:
   ```bash
   openssl rand -hex 32
   ```
3. Store it wherever both services read it, then restart `identity-service` and `org-team-service` back to back.
   Preview and accept requests carrying old links return `INVALID_TOKEN` between the two restarts.
4. Affected invitees need a fresh link. `POST /orgs/{orgId}/invites/{inviteId}/resend` re-mints the token for the same
   invite, subject to the resend cooldown and `max-sends`; for an invite that has reached its cap, revoke and
   re-invite.

For a leaked key, skip the drain: rotate immediately, then revoke every pending invite for the affected
organizations.

**Verify**: create an invite, open its link (`GET /invites/{token}` returns the preview), and confirm acceptance
succeeds in `identity-service`.

## 7. Manually restore a wrongly deleted organization

There is deliberately no API for this. Deletion is a destructive, owner-confirmed action with a recent-authentication
check, and an undelete endpoint would give a stolen session the same power to reverse an operator's cleanup. Restore is
an operator action, allowed only before the purge job removes the rows (`pallet.orgteam.retention.deleted-orgs`, 30
days after `deleted_at`).

**Check the window**:

```sql
SELECT org_id, slug, status, deleted_at, deleted_by, purged_at,
       deleted_at + interval '30 days' AS purge_after
FROM org_team.organizations WHERE org_id = :orgId;
```

If `purged_at` is set the rows are gone and only the slug tombstone remains; this procedure cannot help.

**What deletion changed, and what cannot come back**: memberships became `REMOVED` with `removed_at` equal to the
organization's `deleted_at`, apps became `DELETED` with the same `deleted_at`, and pending invites became `REVOKED`
(their tokens stay dead). Team assignments were deleted and are not recoverable. `identity-service` disabled every
account under the organization when it consumed `OrgDeleted`; that is not undone here.

**Procedure**, in one transaction:

```sql
BEGIN;

UPDATE org_team.memberships m
SET status = 'ACTIVE', removed_at = NULL, removed_by = NULL, version = version + 1
FROM org_team.organizations o
WHERE o.org_id = :orgId AND m.org_id = o.org_id
  AND m.status = 'REMOVED' AND m.removed_at = o.deleted_at;

UPDATE org_team.apps a
SET status = 'ACTIVE', deleted_at = NULL, updated_at = now(), version = version + 1
FROM org_team.organizations o
WHERE o.org_id = :orgId AND a.org_id = o.org_id
  AND a.status = 'DELETED' AND a.deleted_at = o.deleted_at;

UPDATE org_team.organizations
SET status = 'ACTIVE', deleted_at = NULL, deleted_by = NULL, updated_at = now(), version = version + 1
WHERE org_id = :orgId AND status = 'DELETED' AND purged_at IS NULL;

COMMIT;
```

Matching on `removed_at = deleted_at` restores only the members deletion removed; anyone removed earlier stays
removed. Restoring apps can fail on `ux_apps_active_slug` if someone reused a slug in the meantime; rename the newer
app first.

**Then, outside this database**:

1. Re-enable each restored member's Keycloak user (admin console, realm `pallet`). This service holds no Keycloak
   Admin credential by design, so nothing here does it for you.
2. Tell the owner that teams and their assignments, and every invite that was pending, must be recreated.
3. Do not publish events by hand. `OrgDeleted` and `AppDeleted` were already delivered, and consumers that acted on
   them (notification-service, future scheduler and deploy services) need their own recovery.

**Verify**: `GET /orgs/{orgId}` as the owner returns the organization, the member count matches the restored rows, and
the owner can sign in.
