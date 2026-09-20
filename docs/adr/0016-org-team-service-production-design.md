# 16. org-team-service: transactional outbox and inbox, local-row authorization, invite-rejection compensation

- Status: accepted
- Date: 2026-09-20

## Context

`docs/org-team-service/ARCHITECTURE.md` was first written as a proposed design alongside
`identity-service`'s (ADR-0011) and reviewed against what has since been built: `identity-service`
in full, `api-gateway`, the session-revocation registry (ADR-0015), and the shared events and
messaging modules. Four places where the first draft's reasoning does not hold for this service:

1. **Publishing after commit is a dual write, and here a lost event is a security failure.**
   `identity-service` accepted the dual-write gap (its checkpoint 12) because it has a
   *compensating action*: it writes Keycloak first and deletes the orphaned account if the local
   commit fails, so the residual gap is a doubly rare cleanup failure. `org-team-service` has no
   such compensation. Its events are the *mechanism* by which access is withdrawn: `OrgMemberRemoved`
   and `OrgMemberRoleChanged` are what make `identity-service` disable an account or change a
   role. A process crash between the database commit and the Kafka send leaves the member removed
   locally and still enabled in Keycloak, silently and permanently. `docs/PROJECT.md` defers the
   outbox to Phase 8 "unless an earlier phase hits a concrete correctness problem that forces it
   sooner." This is that problem.
2. **A Redis-backed idempotency guard marks before the effect.** The default
   `EventIdempotencyGuard` claims an event id in Redis, then the listener does its work. A crash
   between the claim and the database commit drops the event permanently: the redelivery finds the
   claim and skips. That is tolerable for a notification. For `OrgInviteAccepted` (the only path
   that creates a membership) or `OrgProvisioned` (the only path that creates an org) it is data loss.
3. **Authorizing from the token's roles gives a demoted or removed member up to 15 minutes of
   access to data this service owns.** The first draft accepted that as the standard cost of
   stateless JWT authorization. It is the standard cost for a service that *has no other source of
   truth*. This service is the source of truth for roles and holds the membership row locally.
4. **Dropping an `OrgInviteAccepted` for a revoked invite leaves a live Keycloak account.** The
   first draft (and ADR-0011) named this as an accepted consequence of having no synchronous call.
   The account carries an `org_id` claim and a role for an organization that refused it; every
   *other* Pallet service trusts the token and will honour it. That is a tenant-isolation gap, not
   a cosmetic inconsistency, and it can be closed without any synchronous call.

## Decision

**1. Transactional outbox for every event this service publishes.** State change and event row are
written in one Postgres transaction (`OutboxWriter.append`); a service-local `OutboxRelay` publishes
them afterwards through `PlatformEventPublisher`. Single active relay via a Postgres advisory lock;
per-org ordering preserved (a failed row holds back only its own org); a bounded retry then
`PARKED`, which alerts and keeps that org's later events queued rather than reordering its
history. Delivery is at-least-once with a stable `eventId`. Payloads flagged `sensitive` (the
invite's raw token) are nulled on publish. Polling, not Debezium, for now; `OutboxWriter` is the
only seam writers know, so Debezium later replaces the relay alone.

This **narrows** the Phase 8 deferral to "for `org-team-service`, now". It does not reverse
`identity-service`'s checkpoint 12 decision, which stands for its own reasons (point 1).

**2. Transactional inbox for every event this service consumes.** Listeners are `@Transactional`
and claim `(event_id, consumer)` in `processed_events` (`TransactionalInbox`) in the same
transaction as their effect. The service does not use the messaging module's Redis
`EventIdempotencyGuard`; an architecture test forbids referencing it. `platform-common-messaging`'s
retry and dead-letter wiring is still used unchanged.

**3. Authorization reads the caller's local membership row.** Every `/orgs/{orgId}/...` request
passes a tenant gate (path `orgId` must equal the token's `org_id`, else `404`) and a membership
gate (one primary-key read yields the current local role and status). The token's
`realm_access.roles` are not consulted for this service's decisions. A removed member gets `403`
and a demoted admin loses admin rights on the next request, not the next token refresh. Sensitive
operations (org delete, ownership transfer) additionally require a recent `auth_time`.

**4. `OrgInviteRejected`, a new event, compensates a repudiated accept.** When
`OrgInviteAccepted` arrives for an invite that is revoked, expired, unknown, or belongs to a
deleted org, this service grants no membership and publishes `org.invite.rejected`; `identity-service`
gains a listener that disables the account it created. Still fully asynchronous; the only sanctioned
handoffs between the two services remain the signed token and events.

Supporting decisions recorded here so they are not re-litigated:

- **One owner, enforced twice**: `MembershipPolicy` for error messages, a partial unique index on
  `memberships(org_id) WHERE role='OWNER' AND status='ACTIVE'` for correctness. Ownership transfer
  emits the promotion before the demotion so Keycloak never transiently has no owner.
- **The org row is the lock root** for membership-affecting operations (`SELECT … FOR UPDATE`),
  with optimistic `version` on each aggregate for everything else.
- **`invites.id` is the token `jti`** and the `inviteId` in `OrgInviteAccepted`: one identifier for
  one thing.
- **Roles cross service boundaries as lowercase Keycloak realm-role names** (`owner`, `admin`,
  `developer`, `viewer`), because `identity-service` passes them directly to the Admin API.
- **Tenant consistency in the schema**: composite foreign keys make a team member from another org
  unrepresentable; a trigger makes an app's provider/region immutable at the database level.
- **`SignedActionToken` here both issues and verifies** (ADR-0011 said issue-only). The public
  invite-preview endpoint verifies the signature before any database read, which is what keeps that
  unauthenticated endpoint cheap to abuse-proof.

## Consequences

**Easier**: a removed member's account is disabled *eventually and certainly*, not probabilistically;
Kafka being down never fails a user's request (it only delays propagation, visibly); a redelivered
or crashed-mid-handler event can neither be lost nor double-applied; a revoked invite's account
is actually disabled; revocations and demotions bite immediately inside this service.

**Harder / new work this creates**:

- A relay, an outbox table with retention, an inbox table with retention, and the operational
  surface that comes with them: lag, parked-row, and DLT alerts and runbooks (checkpoints 03 and 14).
  Real complexity, deliberately paid here and not yet in services where it is not needed.
- One extra primary-key read per authenticated request. Not cached, by design.
- Propagation latency: events reach Kafka within a poll interval (default 250 ms) of commit rather
  than inline. Acceptable; no consumer needs sub-second.
- Cross-service work this forces, scheduled as checkpoints 01 and 16 of
  `docs/workflows/org-team-service/`: four new event records and `Topics` constants in
  `platform-common-events` (`OrgMemberAdded`, `OrgInviteRejected`, `AppCreated`, `AppDeleted`); an
  `ORG_INVITE` template and the membership/`org.deleted` listeners in `notification-service`
  (ADR-0008 reserved this seam but it was never built); an `OrgInviteRejectedListener` in
  `identity-service`.
- The outbox is service-local, not shared. When a second service needs it, extract it into a
  `platform-common` module in its own change.
- ADR-0011's "accepted consequence" for revoked and orphaned invites is superseded by decision 4.
  It stays true that revocation cannot *prevent* account creation; it now reliably *undoes* it.
- `spring-data-redis` joins this service's dependencies so ADR-0015's registry is Redis-backed here,
  which ADR-0015 itself named as required for any service that validates end-user tokens.

**Not done by this decision**: invite-signing key rotation (needs a dual-key verify window in
`identity-service`); reactivating a removed member; Debezium; row-level security; plan-based
quotas. See `docs/org-team-service/ARCHITECTURE.md` §Open questions and §Extension points.

## Alternatives considered

- **Keep AFTER_COMMIT publishing and accept the gap, as `identity-service` did.** Rejected: the
  justification there was a compensating delete; none exists here, and the failure mode is a
  removed member retaining access with no signal that it happened.
- **Publish inside the transaction (send, then commit).** Rejected: the send can succeed and the
  commit fail, publishing a fact that never became true, which is worse than losing one.
- **Debezium/CDC on the outbox table now.** Deferred, not rejected: the same guarantee with more
  infrastructure (Kafka Connect, a connector, WAL configuration) before there is a deployment target
  to justify it. The seam is preserved.
- **Redis guard plus natural-key idempotency only.** Rejected for the accept and provision paths
  (point 2); natural keys alone do not cover every effect (an outbox row, a status flip).
- **Trust the token's roles and shorten the access-token lifetime.** Rejected: it shrinks the
  window but never closes it, and costs platform-wide refresh traffic to avoid one local read.
- **Cache the membership lookup.** Rejected: reintroduces the staleness this exists to remove; the
  read is a primary-key hit on a small table.
- **Drop the orphaned invite accept and log it (ADR-0011's stance).** Rejected per Context point 4.
- **A synchronous "is this invite still valid" call from `identity-service`.** Rejected outright:
  the platform's one hard rule.
- **Extract the outbox into `platform-common` immediately.** Rejected: `AGENTS.md` forbids a shared
  module ahead of the feature that needs it, and a second consumer's real requirements will shape
  the abstraction better than a guess.
