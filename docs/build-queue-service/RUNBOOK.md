# build-queue-service — Runbook

Operator procedures for the alerts in
[`deploy/local/prometheus/rules/build-queue-service.rules.yml`](../../deploy/local/prometheus/rules/build-queue-service.rules.yml)
and the failure modes in [ARCHITECTURE.md](ARCHITECTURE.md#consistency-and-failure-modes). Each alert section is
detect → diagnose → fix → verify. SQL runs against the `pallet` database, schema `build_queue`; Kafka commands assume
the local stack (`pallet-kafka`, tools under `/opt/kafka/bin`). The dashboard is
[`deploy/local/grafana/dashboards/build-queue-service.json`](../../deploy/local/grafana/dashboards/build-queue-service.json).

Three rules hold for every procedure here:

- **Never change a build's `status` by hand.** Every transition is a guarded update that also writes its event to the
  outbox; a hand edit publishes nothing, so `build-service` and every later consumer disagree with the table. The
  queue frees slots through results, cancellations and deadlines only.
- **Never free a slot because Kafka was unreachable.** A build whose result is stuck in transit is still running as
  far as the queue knows. The lease sweeper already refuses to expire anything while it can't hear the backbone.
- **Never paste a commit message, author, repository or failure reason into a ticket or a log search.** They're
  untrusted text kept only for display; none of them is logged. If you find one in a log, that is a bug to file.

The queue gauges are read from the database by every instance, so they agree across instances: read them with `max`,
never `sum`.

## Reading the queue

```sql
-- What is waiting and what holds a slot, platform-wide.
SELECT status, priority_class, count(*), min(enqueued_at) AS oldest
  FROM build_queue.builds
 WHERE status IN ('QUEUED', 'DISPATCHED', 'RUNNING')
 GROUP BY status, priority_class;

-- In flight, oldest first, with the deadline the lease sweeper will act on.
SELECT build_id, org_id, app_id, status, attempt, dispatched_at, start_deadline, started_at, lease_expires_at
  FROM build_queue.builds
 WHERE status IN ('DISPATCHED', 'RUNNING')
 ORDER BY dispatched_at;

-- Waiting builds nothing but platform capacity or the dispatcher holds (the stall alert's gauge).
SELECT b.org_id, b.app_id, b.build_id, b.priority_class, now() - b.enqueued_at AS waited
  FROM build_queue.builds b
 WHERE b.status = 'QUEUED'
   AND NOT EXISTS (SELECT 1 FROM build_queue.builds f
                    WHERE f.app_id = b.app_id AND f.status IN ('DISPATCHED', 'RUNNING'))
 ORDER BY b.enqueued_at
 LIMIT 20;
```

The last query doesn't apply the org limits; compare each org's in-flight count with its profile
(`pallet.build-queue.limits`). A developer sees the same reasoning per build as `waitingOn` in
`GET /api/v1/build-queue/orgs/{orgId}/queue`.

Every status change is one log line from `io.pallet.buildqueue.observability.TransitionLog`:
`Build transition buildId=… orgId=… appId=… from=… to=… reason=… attempt=…`. Search by `buildId` to see a build's
whole life; the line carries the trace id of the work that moved it.

## Reading the outbox

```sql
SELECT status, event_type, count(*), min(created_at) AS oldest
  FROM build_queue.outbox_events
 WHERE status <> 'PUBLISHED'
 GROUP BY status, event_type;
```

`build.dispatched` rows waiting here are builds that hold a slot but haven't reached `build-service` yet.

## Advisory locks

Single-active jobs take a transaction-scoped advisory lock. In `pg_locks` a key shows as `classid = 1` and:

| Job | `objid` |
|---|---|
| Dispatcher round | `3010154305` |
| Lease sweep | `3010154306` |
| Retention run | `3010154307` |
| Outbox relay | `3010154114` |

```sql
SELECT l.objid, a.pid, a.application_name, a.state, now() - a.xact_start AS in_transaction, left(a.query, 120)
  FROM pg_locks l JOIN pg_stat_activity a USING (pid)
 WHERE l.locktype = 'advisory' AND l.classid = 1 AND l.granted;
```

---

## 1. Dispatch stalled

**Detect**: `BuildQueueDispatchStalled` (page): `build_queue_oldest_dispatchable_age_seconds` above 300 for five
minutes while slots are free and not every instance is paused. A build no app or org limit holds isn't being
dispatched.

**Diagnose**:

1. Are rounds running, and how do they end? `sum by (result) (rate(build_queue_dispatch_round_seconds_count[5m]))`.
   - Only `lock_busy`: something holds the dispatcher lock. Run the [advisory lock query](#advisory-locks) for
     `objid = 3010154305`; a transaction open for more than a second is the culprit.
   - `error`: rounds are failing. Search the logs for `Dispatch round failed`; the stack trace names the statement.
     A unique violation on `ux_builds_app_in_flight` is a bug, never something to work around.
   - No rounds at all: `pallet.build-queue.dispatch.wakeup-enabled` is off on every instance, or every instance is
     down. `build_queue_dispatch_listen_connected` at 0 alone only costs latency; the poll still runs rounds.
   - Only `idle` while the query above returns rows: the candidate query and the gauge disagree. Check the org is
     `ACTIVE` (`SELECT org_id, status, last_served_at FROM build_queue.orgs WHERE org_id = '…'`) and its profile.
2. Is one instance paused and the others not? `build_queue_dispatch_paused` by instance. A paused instance skips its
   rounds; the others should still dispatch.

**Fix**: end the transaction holding the lock (`SELECT pg_terminate_backend(<pid>)` once you know what it is), or
restart the instance whose rounds fail. Rounds are one short transaction each; restarting loses nothing.

**Verify**: `build_queue_dispatched_total{attempt="1"}` rises and the gauge falls under the next poll.

## 2. Saturated

**Detect**: `BuildQueueSaturated` (ticket): every slot of `dispatch.global-concurrency` in use for fifteen minutes.

**Diagnose**:

1. Is the work real? The in-flight query above: long-running builds with fresh `started_at` are; `DISPATCHED` builds
   near their `start_deadline` mean `build-service` isn't starting them (see [3](#3-redispatching)).
2. Is one org taking most slots? `SELECT org_id, count(*) FROM build_queue.builds WHERE status IN ('DISPATCHED',
   'RUNNING') GROUP BY org_id ORDER BY 2 DESC;` Its profile caps it; round robin keeps others' turns.
3. How long do builds wait? `build_queue_wait_seconds` p95 by `priority_class`.

**Fix**: a capacity signal, not a fault. Raise `build-service`'s capacity first, then `dispatch.global-concurrency`
to match. Never raise the queue's setting past what `build-service` can run: dispatched builds would wait inside it,
miss `startBy` and be redispatched for nothing.

**Verify**: in flight drops below capacity and the wait p95 falls.

## 3. Redispatching

**Detect**: `BuildQueueRedispatching` (ticket): `build_queue_dispatched_total{attempt!="1"}` rising for ten minutes.
Builds miss their `startBy`.

**Diagnose**:

1. Is `build-service` consuming `build.dispatched`? Its consumer group's lag on the topic:
   `docker exec pallet-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <build-service group>`.
2. Is the relay late? [6](#6-outbox-backlog): a dispatch stuck in the outbox burns its start window too.
3. How late do starts come? `build_queue_start_latency_seconds` p95 against `dispatch.start-timeout`.

**Fix**: restore `build-service`. If it's down for known maintenance, [drain](#9-drain-before-build-service-maintenance)
instead of letting attempts run out. Raise `dispatch.start-timeout` only if `build-service` is healthy but slow to
pick up.

**Verify**: redispatches stop and `build_queue_finished_total{reason="START_TIMEOUT"}` stays flat.

## 4. Lease expiries

**Detect**: `BuildQueueLeaseExpiries` (ticket): `build_queue_finished_total{outcome="expired"}` with `START_TIMEOUT`
or `LEASE_EXPIRED` rising for ten minutes.

**Diagnose**:

1. Which deadline? `START_TIMEOUT` is [3](#3-redispatching) run to the end. `LEASE_EXPIRED` means a build started and
   never reported a result.
2. ```sql
   SELECT build_id, org_id, app_id, status_reason, attempt, started_at, finished_at
     FROM build_queue.builds
    WHERE status = 'EXPIRED' AND finished_at > now() - interval '1 hour'
    ORDER BY finished_at DESC;
   ```
3. Did results come later? `build_queue_events_anomaly_total{kind="late_result"}` rising means results are stuck in
   `build-service`'s outbox past `lease.grace`, not lost.

**Fix**: on `build-service`'s side: builders dying mid-build, or its outbox stalled. Raise `lease.grace` only for
known transit delay. Expired builds free their slots on their own; late results are still recorded.

**Verify**: the expiry rate returns to zero.

## 5. Contract violation

**Detect**: `BuildQueueContractViolation` (page): `build_queue_events_anomaly_total` with `kind` `late_start` or
`impossible`. `build-service` started a build after the queue gave up on it, or reported a result for a build that
never left the queue.

**Diagnose**:

1. The WARN lines: `Build report anomaly kind=… buildId=… status=… eventId=…`.
2. ```sql
   SELECT build_id, org_id, app_id, status, status_reason, attempt, start_deadline, started_at, finished_at
     FROM build_queue.builds WHERE build_id = '<buildId>';
   ```
3. For `late_start`: compare the dispatch's `startBy` (`start_deadline`) with when `build-service` started. More
   than clock skew means it ignored `startBy`; another build of the same app may have run beside it.

**Fix**: a `build-service` bug ([ARCHITECTURE.md §Contract with build-service](ARCHITECTURE.md#contract-with-build-service)).
Nothing to repair here: the queue kept the build `EXPIRED` and never gave the slot back.

**Verify**: no new anomalies of either kind.

## 6. Outbox backlog

**Detect**: `BuildQueueOutboxBacklog` (ticket): the oldest unpublished outbox row older than 60 s.

**Diagnose**:

1. [Reading the outbox](#reading-the-outbox). `PENDING` rows with a growing age and `build_queue_outbox_relay_active`
   at 0 on every instance: no relay holds its lock; at 1 somewhere, the relay can't reach Kafka.
2. Is Kafka up? The lease sweeper agrees if `build_queue_lease_sweep_skipped_total` is rising.
3. `build_queue_outbox_held_back` above 0: an older open transaction holds back publishing (ADR-0017). Run the
   [advisory lock query](#advisory-locks) and look for a long `in_transaction`.

**Fix**: restore Kafka; the relay drains in commit order by itself. Dispatched builds hold their slots meanwhile, and
start deadlines and leases are held until the backbone is heard again, then pushed out by `lease.grace`.

**Verify**: the age falls to 0 and `build.dispatched` reaches `build-service`.

## 7. Consumer lag

**Detect**: `BuildQueueConsumerLag` (ticket): more than 1000 `git.push.received` records unread for five minutes.

**Diagnose**:

1. Is intake failing and retrying? Error logs from `PushListener`, and `messaging_dead_letter_total` for
   `git.push.received.DLT`.
2. Is Postgres slow? Intake is one short transaction per push; a lock wait on an org row shows in `pg_stat_activity`.
3. `docker exec pallet-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group build-queue-service`.

**Fix**: the cause above. More instances only help up to the topic's partition count. Coalescing absorbs the burst
once intake catches up: ten pushes to one branch become one waiting build.

**Verify**: the lag returns under 1000 and keeps falling.

## 8. Dead letters

**Detect**: `BuildQueueDeadLetters` (page): a record reached the `.DLT` of a topic this service consumes. The DLT is
shared by every consumer of its source topic, so check that this service put it there.

**Diagnose**: read the record's headers, never its value, for the exception:

```bash
docker exec pallet-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic git.push.received.DLT --from-beginning --max-messages 20 \
  --property print.headers=true --property print.key=true --property print.value=false
```

- `MalformedPushException`: a push that can't become valid on retry (a field missing or out of shape, or a record
  key that isn't its `orgId`). It's an upstream bug in `git-integration-service`.
- `AppOrgMismatchException`: an `appId` arrived under a second org. Never applied; a security or upstream bug.
- Anything else: this service's listener failed every retry. Fix the cause first.

**Fix**: fix the cause, then [replay](#11-replay-a-dead-lettered-push) if the record should still apply.

**Verify**: no new records on the DLT.

---

## 9. Drain before `build-service` maintenance

`dispatch.paused` is read at startup, so pausing is a configuration change on every instance:

1. Set `pallet.build-queue.dispatch.paused: true` in `config-repo/build-queue-service.yml` and restart every
   instance, one at a time. Intake, results and leases keep working; waiting builds show `DISPATCH_PAUSED`.
2. Confirm every instance reports `build_queue_dispatch_paused = 1`. One unpaused instance keeps dispatching and
   keeps processing start deadlines.
3. Wait for `max(sum without (status) (build_queue_in_flight))` to reach 0: every in-flight build has reported or
   run out its lease.
4. Do the maintenance.
5. Set `paused: false` and restart the instances. Builds whose start deadline passed during the pause are
   redispatched with a fresh one; nothing expired except by queue age (`queue.max-age`).

## 10. Rebuild the membership read model

When `org_memberships` is suspect (a bad deploy of the projection, a restored database):

1. Stop every instance, or stop the consumer group `build-queue-service.membership-state`.
2. `TRUNCATE build_queue.org_memberships;`
3. Reset the group:
   ```bash
   docker exec pallet-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
     --group build-queue-service.membership-state --topic org.membership.changed --reset-offsets --to-earliest --execute
   ```
4. Start the instances. The compacted topic replays the latest record of every membership; the version check makes
   the replay idempotent. Until a caller's record is back they get `404 ORG_NOT_FOUND`.

`apps` and `orgs` are never rebuilt this way: they hold tombstones and `last_served_at` the topics can't reproduce,
and builds reference them.

## 11. Replay a dead-lettered push

Intake is idempotent on the push's event id (the inbox) and on `buildId`, which is derived from it, so a replay of
the same record with the same event id builds at most once.

1. Inspect the record's headers ([8](#8-dead-letters)) and fix the cause. A `MalformedPushException` record is
   malformed for good: replay it only once `git-integration-service` republishes a corrected push, which has its own
   event id.
2. Copy the record back to `git.push.received` with its key, value and `event-id` header unchanged, keyed by its
   `orgId`, for example with `kcat`:
   ```bash
   kcat -b localhost:29092 -C -t git.push.received.DLT -o <offset> -c 1 -f '%k\t%s\n' > /tmp/push.tsv
   kcat -b localhost:29092 -P -t git.push.received -K '\t' -H "event-id=<eventId>" -H "event-type=git.push.received" \
     -H "org-id=<orgId>" /tmp/push.tsv
   ```
3. Watch the transition log for its `buildId` (`QUEUED`, or a drop if its app or org has since been deleted).

## 12. Release a stuck app

There is no "free the slot" action, by design: an app's slot comes back when its build reports, is cancelled before
dispatch, or a deadline passes while the backbone is reachable. A slot that stays taken means a deadline isn't being
acted on:

1. Find the build: `SELECT build_id, status, attempt, start_deadline, lease_expires_at FROM build_queue.builds WHERE
   app_id = '<appId>' AND status IN ('DISPATCHED', 'RUNNING');`
2. Is the sweeper skipping? `build_queue_lease_sweep_skipped_total` rising, and
   `SELECT unreachable_since FROM build_queue.lease_sweeper_state;` not null: the backbone is unreachable from the
   instance that wins the sweep. Fix Kafka or the `build.*` listener containers; deadlines resume with `lease.grace`
   added.
3. Is any instance sweeping? `pallet.build-queue.lease.sweeper-enabled` on at least one instance, and no long
   transaction holding `objid = 3010154306` ([advisory locks](#advisory-locks)).
4. Is dispatch paused? Start deadlines wait for it ([9](#9-drain-before-build-service-maintenance)); run leases don't.

If the deadline is far away because `build.max-duration` is long, the build is still within its contract: wait, or
have `build-service` stop it.

---

## Verified failure modes

The chaos walk of [ARCHITECTURE.md §Consistency and failure modes](ARCHITECTURE.md#consistency-and-failure-modes)
against the local stack (`make up`, `make obs`), two instances (`SERVER_PORT=8086` and `8096`), and
`scripts/stub-builder.sh`. After each run, the invariant queries below must return no rows.

| # | Scenario | How to produce it | Expected | Result |
|---|---|---|---|---|
| 1 | Kafka down 2 minutes during steady pushes | `scripts/send-push.sh` in a loop; `docker stop pallet-kafka`, wait 120 s, `docker start pallet-kafka` | No expiries; `BuildQueueOutboxBacklog` fires; the outbox drains in order; every build runs once (`starts` per `buildId` in the stub log is 1) | Not yet run |
| 2 | Postgres down 1 minute | `docker stop pallet-postgres`, wait 60 s, start | Listeners back off without committing offsets; API answers `503`; nothing lost once it's back | Not yet run |
| 3 | An instance killed mid-dispatch | Queue a burst, then `kill -9` the instance holding the dispatch lock ([advisory locks](#advisory-locks)) | Nothing published for the rolled-back round; the other instance dispatches | Not yet run |
| 4 | Stub builder stopped 10 minutes | Stop `stub-builder.sh` with builds dispatched | Redispatches, then `START_TIMEOUT`, slots freed; `BuildQueueRedispatching` and `BuildQueueLeaseExpiries` fire; the queue drains once restarted | Not yet run |
| 5 | Builder dies mid-build | `stub-builder.sh --start-only` | `LEASE_EXPIRED` after `max-duration + grace`; the app's next build runs | Not yet run |
| 6 | Builder ignores `startBy` | `stub-builder.sh --late-start <seconds past start-timeout>` with a short `dispatch.start-timeout` | `late_start` anomaly; `BuildQueueContractViolation` fires; the build stays `EXPIRED` | Not yet run |
| 7 | Poison pushes | A push with a bad `commitSha`, and one for an app that exists under another org | Both on `git.push.received.DLT`; `BuildQueueDeadLetters` fires; intake keeps consuming | Not yet run |

Automated counterparts run in CI: `BackboneOutageIntegrationTest` (1, Kafka paused), `DispatcherIntegrationTest`
(3, a round rolled back at commit), `StartDeadlineIntegrationTest` (4), `RunLeaseIntegrationTest` (5),
`LifecycleOrderingIntegrationTest` (6), `IntakeIntegrationTest` (7).

### Invariant queries

```sql
-- At most one build in flight per app.
SELECT app_id, count(*) FROM build_queue.builds
 WHERE status IN ('DISPATCHED', 'RUNNING') GROUP BY app_id HAVING count(*) > 1;

-- At most one waiting build per app and branch.
SELECT app_id, branch, count(*) FROM build_queue.builds
 WHERE status = 'QUEUED' GROUP BY app_id, branch HAVING count(*) > 1;

-- Every superseded build points at a newer request for the same app and branch.
SELECT o.build_id FROM build_queue.builds o JOIN build_queue.builds n ON n.build_id = o.superseded_by
 WHERE o.status = 'SUPERSEDED' AND (n.app_id <> o.app_id OR n.branch <> o.branch OR n.enqueued_at < o.enqueued_at);

-- No org over its concurrency limit (compare with its profile).
SELECT org_id, count(*) FROM build_queue.builds
 WHERE status IN ('DISPATCHED', 'RUNNING') GROUP BY org_id;
```
