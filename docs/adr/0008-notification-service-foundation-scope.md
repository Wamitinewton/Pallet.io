# 8. notification-service foundation ships in-app + broadcast, not email-only

- Status: accepted
- Date: 2026-09-08

## Context

`docs/PROJECT.md`'s roadmap item 1 and `docs/workflows/ROADMAP.md` Phase 1c originally scoped
`notification-service` as email-only, backed by a single `delivery_log` table that doubles as the
idempotency record. `docs/notification-service/ARCHITECTURE.md` (written before implementation
started) expands that: a second channel (in-app, first-class because delivery *is* persistence
for it), an `audience` concept (`SINGLE` | `ORG`) with org-wide broadcast fan-out, a local
`org_members` read-model seam for resolving broadcasts without a synchronous call to the
not-yet-built `org-team-service`, and a non-blocking per-org rate limiter so one noisy tenant
can't degrade delivery for others.

This is an architecture-changing decision (`CONTRIBUTING.md`: "a decision that changes
architecture gets an ADR in the same PR"), and `ARCHITECTURE.md` itself calls out that it needs
one before `docs/workflows/notification-service/` Sprint 1 starts.

## Decision

Build the wider foundation now, per `docs/notification-service/ARCHITECTURE.md`, instead of
shipping email-only and retrofitting in-app/broadcast later:

- Two channels ship together: **email** (SMTP via Mailpit locally) and **in-app** (delivery row
  = read-side record). Slack, tenant webhooks, and other channels stay deferred — they plug into
  the same `NotificationChannel` interface later with no change to the consumer or read API.
- The data model is the two-table `notifications` / `notification_deliveries` split (not the
  originally-sketched single `delivery_log`), plus an independent `org_members` local projection.
  The idempotency guard still keys off a unique-constraint insert, just against
  `notifications.source_event_id`.
- `audience` (`SINGLE` default | `ORG`) is added to `NotificationRequested` in
  `platform-common-events` now, additively (absent/null stays `SINGLE`, so every existing
  producer keeps working unchanged).
- Broadcast resolves against the local `org_members` projection, not a synchronous call to
  `org-team-service` — consistent with the platform's "no service makes a blocking call to
  another" rule. Until `org-team-service` exists and publishes `org.member.added` /
  `org.member.removed`, an `ORG` broadcast resolves to zero recipients (visible via a
  `notifications.broadcast_empty` counter) rather than failing — the seam is real, the upstream
  producer isn't, yet.
- Per-org rate limiting is a non-blocking permission check on the send path (Resilience4j
  `RateLimiter`, zero-wait), never a block on the Kafka poll loop; rejections become `THROTTLED`
  deliveries drained by an independent `@Scheduled` sweep.
- Every notification and delivery row is retained in full — no TTL, no purge job.

`docs/workflows/notification-service/00-README.md` and its per-sprint files replace the old
6-sprint Phase 1c table in `docs/workflows/ROADMAP.md` with this wider, still small-per-checkpoint
breakdown.

## Consequences

**Easier**: adding a channel later (Slack, webhooks) is additive with zero consumer/read-API
change; a dashboard can list in-app notifications from day one instead of that becoming a second
project; broadcast "just works" the day `org-team-service` starts publishing membership events,
with no code change in this service.

**Harder / new work this creates**:

- `platform-common-events`: `NotificationRequested` gains `audience` (Sprint 2 of the new
  breakdown). This is an additive field, not a breaking change, but it is a shared-module change
  that needs its own review.
- `platform-common-security` becomes a dependency of `notification-service` (for the read API),
  which it wasn't in the email-only sketch.
- Two new REST endpoint groups (list/read/unread-count) exist from the start, with the auth-scoping
  rules that come with exposing user-facing read state.
- `org.member.added` / `org.member.removed` are reserved conceptually here but **not yet** added
  to `platform-common-events`' `Topics` — that happens when `org-team-service`'s own workflow
  defines their exact payload. This service's `OrgMembershipEventListener` and the topic
  reservation are out of scope until then; tracked as a follow-up, not silently dropped.
- `docs/workflows/ROADMAP.md` Phase 1c's checkpoint table needs to point at the new sprint list
  instead of the old email-only one (done in this same change).

**Not done**: real-time push (WebSocket/SSE) for in-app, per-user notification preferences, a
schema registry. See `ARCHITECTURE.md`'s Non-goals and Open questions sections — those remain
deferred, this ADR doesn't reopen them.

## Alternatives considered

- **Ship email-only now, add in-app/broadcast as a later phase.** Rejected: in-app's delivery
  row *is* the read-side record, so bolting it on later means a migration and reshaping
  `notification_deliveries` after real data exists, whereas building it into the same table now
  costs nothing extra. Broadcast has the same shape of argument for `audience` and `org_members`.
- **Resolve `ORG` broadcasts with a synchronous call to `org-team-service`.** Rejected outright —
  breaks the platform's no-blocking-cross-service-call rule and makes every broadcast's latency
  and availability depend on a second service being up.
