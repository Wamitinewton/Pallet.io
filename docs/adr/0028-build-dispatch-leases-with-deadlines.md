# 28. Dispatch leases with deadlines on the message

- Status: accepted
- Date: 2026-10-16

## Context

`build-queue-service` hands a build to `build-service` as a `build.dispatched` event and learns
what happened only from `build.started`, `build.succeeded` and `build.failed` coming back (ADR-0027).
There is no synchronous channel between them, so silence is the only failure signal, and silence
is ambiguous: the builder may be dead, slow, or still reading a topic; the result may be stuck in
`build-service`'s outbox; or this service may be the one that can't hear. Treated naively, silence
either pins an app's only in-flight slot forever (one crashed builder blocks every later build of
the app) or frees it too early (a second build of the app starts while the first one's result is
still in transit).

Both sides have to agree on what silence means, so this is a cross-service contract that
`build-service`, not designed yet, has to honour. The full design is
`docs/build-queue-service/ARCHITECTURE.md` §Leases and deadlines and §Contract with build-service.
This record lands with the lease sweeper, the first code that depends on it.

## Decision

1. **`buildId` is the end-to-end idempotency key.** `build-service` executes each `buildId` at most
   once. A dispatch for a `buildId` it already holds, under any attempt, is acknowledged and
   ignored. `attempt` tells a redispatch from a duplicate; it is never part of the identity.
2. **Deadlines travel on the message.** `build.dispatched` carries `startBy` (database `now()` plus
   `dispatch.start-timeout` at dispatch) and `maxDurationSeconds`. `build-service` never starts an
   attempt after its `startBy`, and stops a build at `maxDurationSeconds` with `build.failed`.
3. **A missed start is redispatched, then given up.** When `start_deadline` passes with no
   `build.started`, the queue redispatches the same `buildId` with `attempt + 1` and a new
   `startBy`, keeping the slot, up to `dispatch.max-attempts`; then it expires the build with
   `build.cancelled(START_TIMEOUT)` and frees the slot. A published attempt's `startBy` is never
   changed: a new deadline is always a new attempt.
4. **A run lease bounds a started build.** `build.started` leases the build for `build.max-duration
   + lease.grace`. Past it, the queue expires the build with `build.cancelled(LEASE_EXPIRED)` and
   frees the slot.
5. **Deadlines are acted on only while the queue can hear.** Each sweep first checks that the broker
   answers a metadata request within `lease.reachability-timeout` and that every `build.*` listener
   container is running and not stopped by its error handler. If not, start and run deadlines are
   left alone (queue age still applies), and the start of the outage is recorded in Postgres. The
   first sweep that hears again moves every start deadline and run lease due within `lease.grace`
   out to `now() + lease.grace`, and acts on none of them until the next sweep.
6. **A paused dispatcher pauses redispatch.** While `dispatch.paused` is on, start deadlines aren't
   processed at all, so maintenance on `build-service` doesn't burn attempts.
7. **Late results are recorded, never rejected.** An `EXPIRED` build that reports a result moves to
   `SUCCEEDED` or `FAILED` (counted as `late_result`); its slot isn't taken back. A `build.started`
   for an `EXPIRED` build is a contract violation, counted as `late_start` and alerted.

All deadlines are computed from database `now()`, and the sweep runs single-active through a
per-sweep advisory lock, like the dispatcher.

## Alternatives considered

- **Heartbeats from `build-service`.** More traffic, still needs a timeout to act on, and a
  heartbeat topic's consumer lag looks exactly like a dead builder.
- **`build-service` acknowledging each dispatch.** An extra event that proves receipt but not
  progress; the start deadline already measures the thing that matters.
- **Never reclaiming slots.** One crashed builder pins an app forever.
- **Fencing tokens per attempt.** `buildId` idempotency already prevents double execution, and the
  queue doesn't control the resource being written, so there is nothing for a token to fence.
- **Expiring on deadlines regardless of reachability.** A broker outage would expire every running
  build at once and free slots for builds that actually finished, letting a second build of the
  same app overlap the first.

## Consequences

- `build-service`'s design inherits a contract: execute per `buildId` at most once, refuse an
  attempt past `startBy`, stop at `maxDurationSeconds`, publish one terminal event per started
  build, and have room for at least `dispatch.global-concurrency` builds. A builder that queues
  work internally past `startBy` gets every such build redispatched for nothing.
- The two sides' clocks must agree to well within `dispatch.start-timeout`; NTP gives that by
  orders of magnitude, and the grace absorbs it on the run lease.
- A result stuck past the grace lets two builds of one app overlap briefly. The deploy path has to
  deploy a result only if its commit is newer than what's live or in progress
  (`ARCHITECTURE.md` §Changes this design needs elsewhere, item 4).
- An outage costs up to one grace of extra waiting after recovery before any deadline is acted on.
  That is the price of not mistaking a backlog of unread results for silence.
