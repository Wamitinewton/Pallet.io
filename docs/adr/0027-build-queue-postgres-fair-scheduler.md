# 27. Build queue as a Postgres-backed fair scheduler

- Status: accepted
- Date: 2026-10-16

## Context

`PROJECT.md` gives `build-queue-service` one job: decide build priority and concurrency per org
before work reaches `build-service`, so backpressure is handled in one place instead of every push
landing in an unbounded build queue. `git-integration-service` adds two expectations: the queue
coalesces rapid pushes (it may skip commit N when N+1 is already waiting) and never builds an older
commit after a newer one for the same branch. The platform forbids synchronous calls between
services, so whatever the queue decides has to cross to `build-service` as an event.

That makes the queue a scheduler with state. It has to answer questions across orgs ("which org is
next?"), hold rules that must never break under concurrency ("one waiting build per app and
branch", "one build of an app in flight"), and tell a tenant why a build is waiting. The full design
is `docs/build-queue-service/ARCHITECTURE.md` (§Why a queue service at all, §Scheduling policy,
§The dispatcher). This record exists because the service scaffold is the first change that depends
on it: the `build_queue` schema is this decision written as constraints.

## Decision

1. **Queue state lives in Postgres**, in the service's own `build_queue` schema. Kafka stays the
   transport in and out (`git.push.received` in, `build.*` results in, `build.queued`,
   `build.dispatched` and `build.cancelled` out through the transactional outbox, ADR-0020), never
   the queue itself.
2. **The invariants are constraints, not code paths.** Coalescing is a partial unique index
   (`ux_builds_app_branch_queued`: one `QUEUED` build per app and branch); per-app serialization is
   another (`ux_builds_app_in_flight`: one `DISPATCHED` or `RUNNING` build per app). A composite
   foreign key to `apps (org_id, app_id)` pins every build to its app's org, and status checks
   require the columns each status depends on. Intake supersedes the waiting build before
   inserting its replacement, in one transaction; the `superseded_by` foreign key is deferred so
   the old row can point at a row inserted after it.
3. **Fair share is round robin across orgs.** Orgs with an eligible build are served in order of
   `last_served_at`, one build per org per turn, under three caps: one in-flight build per app, the
   org profile's `maxConcurrent` (and `maxQueued` at intake), and the platform-wide
   `dispatch.global-concurrency`. Priority classes (`INTERACTIVE` before `AUTOMATIC`) order builds
   only within an org; orgs are equal.
4. **One dispatcher per round, not per cluster.** Every instance runs the dispatch loop; each round
   is one transaction that starts with `pg_try_advisory_xact_lock`, so one instance wins and the
   others skip. The lock dies with the transaction, so a crashed instance never holds dispatch, and
   there is no leader election. Rounds are woken by `pg_notify` from the transactions that create
   or free work (delivered only on commit), with a poll as the safety net, and are bounded by
   `dispatch.batch-size`.
5. **Work is pushed, not pulled.** A dispatch is a `build.dispatched` event written to the outbox in
   the same transaction as the status change. `build-service` never asks the queue for work.
6. **No Temporal.** ADR-0009 limits it to the deploy and billing sagas. A build here is a short
   state machine driven by events and deadlines, with no compensation step.

## Alternatives considered

- **Kafka partitions as the queue** (`build-service` consumes `git.push.received` directly). A
  consumer can't coalesce without reading ahead and holding what it read; it can serialize per
  app only by blocking the partition, which stalls every other app and org hashed to it; a hot org
  starves every org that shares its partition, and an idle partition can't lend capacity; the
  only backlog bound is retention; and "why is my build waiting?" has no answer.
- **`build-service` pulling work over HTTP.** A synchronous call between two Pallet services, which
  the platform rules out, and it couples the builder's availability to the queue's API.
- **Redis sorted sets.** A second store for the source of truth, a dual write between it and the
  Postgres rows the API and the read models need, and weaker durability for the one record that
  must not be lost once a push is acknowledged.
- **Temporal workflows per build.** Outside ADR-0009's scope, and a heavier runtime for what is a
  handful of guarded `UPDATE`s and a sweeper.
- **Weighted fair queuing between plans.** Deferred until `billing-service` needs a larger share of
  a contended fleet for a plan rather than more concurrency. `LimitProfiles` resolves per org, so
  adding weights later changes the dispatcher's ordering, not the schema.

## Consequences

- Dispatcher throughput is bounded by Postgres round latency (a few indexed queries and one update
  per dispatched build), which is irrelevant next to build durations measured in minutes.
- Each instance holds one Postgres connection for `LISTEN` outside the pool. A pooler in
  transaction mode in front of this service would silently drop `LISTEN` registrations.
- A constraint violation in production means a dispatcher or intake bug. It rolls the transaction
  back, publishes nothing, and surfaces loudly; code must never catch it and retry around it.
- Every lock-taking path (intake, cancellation, dispatch) locks the org row before a build row, so
  none of them can deadlock with another.
- Deadlines and leases between the queue and `build-service` (`startBy`, `maxDurationSeconds`,
  redispatch, reachability-gated expiry) are a separate cross-service contract, recorded in its
  own ADR with the leases checkpoint.
