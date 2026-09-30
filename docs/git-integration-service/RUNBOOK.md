# git-integration-service — Runbook

Operator procedures for the alerts in
[`deploy/local/prometheus/rules/git-integration-service.rules.yml`](../../deploy/local/prometheus/rules/git-integration-service.rules.yml)
and the failure modes in [ARCHITECTURE.md](ARCHITECTURE.md#consistency-and-failure-modes). Each alert section is
detect → diagnose → fix → verify. SQL runs against the `pallet` database, schema `git_integration`; Kafka commands
assume the local stack (`pallet-kafka`). The dashboard is
[`deploy/local/grafana/dashboards/git-integration-service.json`](../../deploy/local/grafana/dashboards/git-integration-service.json).

Two rules hold for every procedure here:

- **Never disconnect or unlink anything because GitHub was unreachable.** Only a definite answer from GitHub changes a
  link.
- **Never paste a token, a private key, a webhook secret, a `code` or a `state` into a ticket, a chat, or a log
  search.** None of them is logged; if you find one in a log, that is a bug to file.

## Reading the delivery table

```sql
SELECT status, count(*), min(received_at) AS oldest, max(attempts) AS most_attempts
FROM git_integration.webhook_deliveries
GROUP BY status;
```

`RECEIVED` rows are waiting for the processor (or for GitHub, or a rate-limit reset: see `next_attempt_at` and
`last_error`). `PROCESSED` and `IGNORED` are done. `PARKED` rows failed every attempt and wait for an operator. The
same numbers are the `git_deliveries_pending`, `git_deliveries_oldest_pending_age_seconds` and
`git_deliveries_parked` gauges, refreshed every `pallet.git.delivery.metrics-interval`.

## Reading the outbox

```sql
SELECT status, count(*), min(created_at) AS oldest
FROM git_integration.outbox_events
WHERE status <> 'PUBLISHED'
GROUP BY status;
```

`GET /actuator/health` shows the same under `outbox`. The outbox never turns readiness `DOWN`: readiness is Postgres
only, because the webhook must keep accepting while Kafka is down.

---

## 1. Deliveries backing up

**Detect**: `GitDeliveriesBackingUp` (warn above 60 s, page above 300 s) on
`git_deliveries_oldest_pending_age_seconds`. Pushes are not turning into builds.

**Diagnose**:

1. Is the processor running? `rate(git_deliveries_processed_total[5m])` should be above zero while
   `git_deliveries_pending` is. `pallet.git.delivery.enabled` must be `true` on at least one instance.
2. Why is the oldest one waiting?
   ```sql
   SELECT delivery_id, event, attempts, next_attempt_at, last_error
   FROM git_integration.webhook_deliveries
   WHERE status = 'RECEIVED' ORDER BY received_at LIMIT 20;
   ```
   - `next_attempt_at` in the future with a `GitHubRateLimitedException` error: that installation is out of budget;
     it resumes at the reset. `git_deliveries_failures_total{kind="rate_limited"}` climbs.
   - An `ExternalServiceException` error: GitHub is failing, see [procedure 6](#6-github-breaker-open).
     `kind="github_unavailable"` climbs; these retries do not spend attempts, so nothing parks.
   - Anything else (`kind="error"`): a bug or a database fault; the row parks after
     `pallet.git.delivery.max-attempts`.
3. Is Postgres slow or locked? A long transaction on `branch_heads` holds every push to that branch; see
   [procedure 18](#18-find-a-long-running-transaction).

**Fix**: the cause above. The processor catches up by itself, `batch-size` deliveries per `poll-interval` per
instance.

**Verify**: the gauge falls to 0 and `git_push_to_outbox_latency_seconds` p99 returns under 3 s.

## 2. Delivery parked

**Detect**: `GitDeliveryParked` (`git_deliveries_parked > 0`) and an `ERROR Delivery parked: ...` log line carrying
`deliveryId` in its MDC.

**Diagnose**:

```sql
SELECT delivery_id, event, action, installation_id, attempts, last_error, received_at
FROM git_integration.webhook_deliveries WHERE status = 'PARKED';
```

- `MalformedPayloadException`: GitHub sent something the strict parser refuses. Keep the row; it is evidence. File a
  bug with the `last_error` and the event type, never the payload.
- Anything else: the cause is in `last_error` and in the logs around it (search by `deliveryId`).

**Fix**: fix the cause first, then re-queue it:

```sql
UPDATE git_integration.webhook_deliveries
SET status = 'RECEIVED', attempts = 0, next_attempt_at = now(), last_error = NULL
WHERE delivery_id = '<guid>' AND status = 'PARKED';
```

Re-queuing is safe: the delivery's effects and events commit in one transaction, the chain rule turns a push that
has since been superseded into `STALE`, and event ids are derived from the delivery GUID, so nothing publishes
twice. A parked payload older than seven days has been nulled by retention: re-queuing it parks again. Replay it
from GitHub instead ([procedure 11](#11-replay-a-delivery-by-guid)).

**Verify**: the row is `PROCESSED` or `IGNORED` and `git_deliveries_parked` returns to 0.

## 3. Signature failures

**Detect**: `GitWebhookSignatureFailures`: `git_webhook_signature_failures_total` is well above its daily baseline.

**Diagnose**:

1. Was the webhook secret just rotated? Compare `git_webhook_signature_verified_total{secret="current"}` and
   `{secret="previous"}`. If verified deliveries dropped to zero at the same moment the failures began, the secret on
   GitHub and `PALLET_GIT_WEBHOOK_SECRET` disagree.
2. Otherwise someone is probing the endpoint. The access log (`pallet.access`) shows the paths and sources; if the
   GitHub IP allowlist is on, `git_webhook_ip_allowlist_total{result="rejected"}` shows how many came from outside
   GitHub's ranges. A forged request costs one HMAC and touches no database.

**Fix**: for a mismatch, put the secret GitHub has back into `PALLET_GIT_WEBHOOK_SECRET` (or `_PREVIOUS`) and
redeploy; GitHub's retries and the redelivery sweeper recover what was refused. For probing, nothing is required; turn
on `pallet.git.webhook.github-ip-allowlist.enabled` if the volume matters.

**Verify**: the failure rate returns to baseline and `git_webhooks_received_total{result="stored"}` resumes.

## 4. Outbox stalled or held back

**Detect**: `GitOutboxStalled` (warn above 60 s, page above 300 s) on `git_outbox_oldest_pending_age_seconds`, or
`GitOutboxHeldBack` on `git_outbox_held_back > 0`. Push events are not reaching `build-queue-service`.

**Diagnose**, in this order:

1. Is Kafka reachable? `Broker unavailable, relay backing off ...` in the logs and
   `git_outbox_publish_failures_total{kind="broker"}` climbing mean the broker. Webhooks are still accepted and
   processed; only the relay waits.
2. Is any relay publishing? `rate(git_outbox_published_total[1m])` should be above zero while `git_outbox_pending` is.
3. `git_outbox_held_back > 0` means the relay is withholding rows until an older open transaction finishes
   (ADR-0017): [procedure 18](#18-find-a-long-running-transaction).
4. Rows for one org only? A parked row blocks that org: [procedure 5](#5-outbox-row-parked).

**Fix**: restore the broker, or end the long transaction. The relay drains in commit order by itself, backing off at
most `pallet.outbox.broker-backoff-max`. Do not restart pods for this.

**Verify**: `git_outbox_oldest_pending_age_seconds` returns to 0.

## 5. Outbox row parked

**Detect**: `GitOutboxParked` (`git_outbox_parked > 0`) and `ERROR Outbox row parked id=... eventId=...`.

**Diagnose**:

```sql
SELECT id, event_id, org_id, event_type, attempts, last_error
FROM git_integration.outbox_events WHERE status = 'PARKED';
```

Later rows of that org wait behind it, so its pushes are not building.

**Fix**: fix the cause in `last_error` (usually a serialization or topic problem after a deploy), then:

```sql
UPDATE git_integration.outbox_events
SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL
WHERE id = <id> AND status = 'PARKED';
```

**Verify**: the row is `PUBLISHED`, `git_outbox_parked` is 0, and the org's later rows drain.

## 6. GitHub breaker open

**Detect**: `GitHubBreakerOpen`: `resilience4j_circuitbreaker_state{name="github-api",state="open"}` for five minutes.

**Diagnose**: check [githubstatus.com](https://www.githubstatus.com) and `git_github_calls_seconds_count` by
`outcome`. Only 5xx, timeouts and connection failures open the breaker; a 4xx or a rate limit never does, so an open
breaker means GitHub (or the network to it) is failing for everyone.

What still works: webhook ingestion, ordinary pushes (the chain rule's fast path needs no GitHub call), reads.
What waits: anomalous pushes needing a compare, links, manual builds, check runs, the recovery jobs. Nothing parks
and nothing disconnects while it lasts.

**Fix**: none on this side. If the network path is at fault (DNS, egress, proxy), fix that. The breaker half-opens
after `wait-duration-in-open-state` and closes on success.

**Verify**: the breaker is `closed`; `git_deliveries_pending` drains.

## 7. Reconciler finding pushes

**Detect**: `GitReconcilerFindingPushes`: `git_reconciler_pushes_found_total` rising for three hours. The backstop is
doing the webhook path's job.

**Diagnose**:

1. GitHub's app settings → Advanced → Recent deliveries: are deliveries failing (non-2xx) or missing?
2. Failing with `401`: [procedure 3](#3-signature-failures). With `403`: the IP allowlist or the gateway.
   With timeouts: gateway routing (`api-gateway/08`) or this service being down.
3. Missing entirely: the app's webhook is inactive or its URL is wrong.
4. `git_redelivery_failed_total` rising: the sweeper can't ask GitHub to redeliver (budget, or the app JWT).

**Fix**: the delivery path. Builds are not lost meanwhile: the reconciler builds the current head of each linked
branch (intermediate commits are skipped, as intended).

**Verify**: `git_reconciler_pushes_found_total` stops rising; `git_reconciler_checked_total{result="not_modified"}`
dominates.

## 8. Re-verification not running

**Detect**: `GitReverificationNotRunning`: `git_reverify_oldest_check_age_seconds` above 48 hours. Links are no
longer checked against GitHub; someone who lost push access may still be deploying.

**Diagnose**:

1. `pallet.git.reverify.enabled` is `true` on some instance.
2. Logs: `Access re-verification run finished ...` once a day; `... run failed: ...` names the cause;
   `... skipped, another instance holds the lock` (at `DEBUG`) on the others is normal.
3. `git_reverify_checked_total{outcome="unknown"}` rising: GitHub answered nothing definite (outage, budget), so the
   links stay due and are retried next run. Nothing is disconnected on `unknown`.
4. `git_reverify_run_duration_seconds` near `interval`: `max-per-run` is too small for the number of links.

**Fix**: the cause; raise `pallet.git.reverify.max-per-run` if the fleet outgrew it.

**Verify**: the gauge drops below `pallet.git.reverify.min-age` after the next run.

## 9. Access denials spiking

**Detect**: `GitAccessDenialsSpiking`: `git_link_access_denied_total` far above its daily baseline.

**Diagnose**: `reason="not_accessible"` rising alone suggests someone walking repository ids on an installation they
don't share with GitHub; `reason="permission_too_low"` suggests a team whose GitHub roles changed. The access log shows
which callers and orgs (by path) are making the `PUT .../repo-link` calls; `git_authz_denied_total` shows whether
the same callers are also failing the membership gate.

**Fix**: nothing is exposed by a denial: the error never distinguishes "the installation can't reach it" from "you
can't". If one caller is probing, revoke their session (ADR-0015) and raise it with the org's owner.

**Verify**: the rate returns to baseline.

## 10. Dead-letter topic is not empty

**Detect**: `GitDeadLetter`: `messaging_dead_letter_total` rose for the `.DLT` of a topic this service consumes
(`app.created`, `app.deleted`, `org.deleted`, `org.membership.changed`, `build.*`, `deploy.state.changed`). The
platform's DLT monitor is one consumer group across every service, so the series can carry any service's `job`
label, and a DLT is shared by every consumer of its source topic: the alert matches on the topic alone.

**Diagnose**: `git_events_failed_total{listener=...}` rising names this service's listener; if it did not rise, the
record came from another consumer of the same topic and belongs to that service's runbook. Read the dead-lettered record's headers
(`kafka-console-consumer --topic <topic>.DLT --property print.headers=true`) for the exception class; never paste
its value into a ticket.

**Fix**: fix the listener, redeploy, then replay the DLT record onto its source topic. Replays are safe: every
listener claims the event id in `processed_events` first, and the membership projection applies only newer versions.

**Verify**: the listener's `git_events_failed_total` stops rising and the read model has the record.

---

## 11. Replay a delivery by GUID

A delivery GitHub never reached us with, or one whose payload retention has nulled.

1. GitHub app settings → Advanced → Recent deliveries, find the GUID (`X-GitHub-Delivery`), **Redeliver**.
2. If the GUID is already a row here, the primary key answers `200` and nothing is processed. Delete the row first
   only if it is `PARKED` and its payload was nulled:
   ```sql
   DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = '<guid>' AND status = 'PARKED';
   ```
3. The redelivery is processed like any other; the chain rule makes a superseded push `STALE`.

The redelivery sweeper does the same automatically for failed deliveries inside `pallet.git.redelivery.lookback`.

## 12. Force a reconcile of one app

Makes the next reconciler run fetch this app's branch first, without its stored `ETag`:

```sql
UPDATE git_integration.branch_heads SET etag = NULL WHERE app_id = '<appId>';
INSERT INTO git_integration.sync_cursors (name, cursor) VALUES ('reconciler', '<installationId - 1>:0')
ON CONFLICT (name) DO UPDATE SET cursor = EXCLUDED.cursor, updated_at = now();
```

The next run (every `pallet.git.reconciler.interval`) starts at that installation. If the branch moved, the push is
published with trigger `RECONCILED`. To build now instead, `POST .../repo-link/builds` builds the branch's current
head.

## 13. Rotate the webhook secret

1. Generate a new secret (at least 32 bytes).
2. Set `PALLET_GIT_WEBHOOK_SECRET_PREVIOUS` to the **current** secret and `PALLET_GIT_WEBHOOK_SECRET` to the new one;
   deploy. Both are accepted now.
3. Change the secret in the GitHub App's settings.
4. Watch `git_webhook_signature_verified_total{secret="previous"}` stop increasing (allow for GitHub's in-flight
   retries, a few minutes).
5. Clear `PALLET_GIT_WEBHOOK_SECRET_PREVIOUS` and deploy.

## 14. Rotate the GitHub App private key

1. GitHub App settings → Private keys → **Generate a private key**. GitHub keeps both keys valid.
2. Put the new PEM in `PALLET_GIT_GITHUB_PRIVATE_KEY` (Vault or the secret store, never a tracked file) and deploy.
   Startup refuses a key that is not RSA of at least 2048 bits.
3. Confirm `git_github_calls_seconds_count{endpoint="installation.token.create",outcome="success"}` keeps increasing and
   no `AppCredentialRejectedException` appears.
4. Delete the old key on GitHub.

Installation tokens already minted stay valid until they expire (at most an hour); nothing else needs clearing.

## 15. Rotate the user-session encryption key

1. Generate 32 random bytes, base64: `openssl rand -base64 32`.
2. Set `PALLET_GIT_USER_SESSION_KEY` and deploy.

Every existing session becomes undecryptable (`git_sessions_undecryptable_total` rises once per session read) and is
treated as absent. Users re-authorize the next time they need GitHub; GitHub already knows the app, so there is no
consent prompt. Sessions expire within `pallet.git.user-session.max-ttl` anyway, so the old keys never need deleting
from Redis.

## 16. Rebuild the membership read model

When `org_memberships` is suspect (a bad deploy of the projection, a restored database):

1. Stop the membership listener: scale the service to zero, or stop the consumer group
   `git-integration-service.membership-state`.
2. `TRUNCATE git_integration.org_memberships;`
3. Reset the group:
   ```bash
   kafka-consumer-groups --bootstrap-server localhost:29092 --group git-integration-service.membership-state \
     --topic org.membership.changed --reset-offsets --to-earliest --execute
   ```
4. Start the service. The compacted topic replays the latest record of every membership; until a caller's record is
   back they get `404 ORG_NOT_FOUND`, which the dashboard retries.

`apps` and `deleted_orgs` are rebuilt the same way from their own topics only while those still retain the history;
never truncate them casually.

## 17. Investigate an installation stuck `SUSPENDED`

```sql
SELECT installation_id, account_login, status, suspended_at, updated_at
FROM git_integration.installations WHERE status = 'SUSPENDED';
```

1. Is it still suspended on GitHub? The account's settings → Applications → Installed GitHub Apps, or the app's
   Advanced tab. If it is, nothing is wrong: pushes are ignored until the account unsuspends it.
2. If GitHub shows it active, the `installation.unsuspend` delivery was lost. Find it in the app's recent deliveries
   and redeliver it ([procedure 11](#11-replay-a-delivery-by-guid)). Its processing flips the row to `ACTIVE` and
   records `git.installation.unsuspended`.
3. Pushes that arrived while it was suspended were ignored, by design. The reconciler builds each linked branch's
   current head on its next run ([procedure 12](#12-force-a-reconcile-of-one-app) to hurry it).

Never update the status by hand: the audit trail and the link checks depend on the lifecycle processor doing it.

## 18. Find a long-running transaction

Needed for `GitOutboxHeldBack` and for pushes stuck on a branch lock.

```sql
SELECT pid, usename, application_name, state, now() - xact_start AS open_for, left(query, 120) AS query
FROM pg_stat_activity
WHERE datname = current_database()
  AND (backend_xid IS NOT NULL OR state = 'idle in transaction')
ORDER BY xact_start;
```

An `idle in transaction` session from a tool (psql, a BI client) is the usual culprit; end it with
`SELECT pg_terminate_backend(<pid>);`. A transaction from this service open for more than a few seconds is a bug:
no GitHub call may run inside one. Capture its query and file it before terminating.
