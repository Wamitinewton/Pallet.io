# 18. Multi-org-per-account and per-request org authorization

- Status: accepted
- Date: 2026-09-22
- Supersedes: ADR-0011 decision 3 ("single Keycloak account = single organization, permanently")
- Amends: ADR-0003 ("org_id claim on every token")

## Context

ADR-0011 made single-org-per-account a deliberate v1 constraint and named its own retrofit cost in
advance, in both architecture documents' Open Questions: "moving `org_id` off the Keycloak user
attribute and resolving it at token-issuance/session time instead ... a breaking change to
ADR-0003, not additive." That day arrived from the product side, not by accident: org invites are
required to work for a person who already has a Pallet account under a different organization —
the ordinary case of someone with their own account being asked to join a second, unrelated org's
workspace, the same shape Vercel, Supabase, and GitHub all support (a personal account plus
membership in any number of team workspaces, joined without a second login).

Two things forced a real design, not a patch to `InviteAcceptService`:

- `identity.users` enforces a hard, global `UNIQUE INDEX` on `email`, and `IdentityUser.orgId` is a
  single column — accepting an invite for an email that already has an account throws Keycloak's
  own `409` back at the caller today ("an account with this email already exists"). That is the
  constraint working as designed, not a bug.
- Once an account can hold more than one org membership, a *session-scoped* active org (a JWT
  `org_id` claim, switched by reissuing a token) cannot deliver what was asked: acting on two
  different orgs from two open tabs with no re-authentication step in between, the way every
  Vercel/Supabase/GitHub dashboard already behaves. Session-scoping and multi-org-per-account are
  in real tension; the fix has to be architectural, and `org-team-service` already built half of it
  by accident.

`org-team-service`'s ADR-0016 decision 3 (built and shipped) already resolves authorization from a
local **membership gate** — a per-request primary-key read of `(userId, orgId)` — specifically
because a token's claims go stale for up to fifteen minutes. The only thing stopping that gate from
being the *entire* authorization decision is the **tenant gate** next to it, which still requires
the path's `orgId` to equal the token's `org_id` — a check that only makes sense if a token can
address exactly one org.

## Decision

**1. One Keycloak account, any number of org memberships.** `identity.users.email` stays globally
unique (one human, one login), but an account is no longer tied to exactly one `org_id`.
`org-team-service`'s `memberships(org_id, user_id)` table already supports this; nothing changes
there. `identity.users.org_id` stops meaning "the account's org" and becomes "the account's
founding org" — the org created at sign-up — nothing more.

**2. Every account has exactly one personal org, and may create or join any number of team orgs.**
`Organization` gains a `kind` column: `PERSONAL` (created once, at sign-up, non-deletable,
non-leaveable, never receives an invite) or `TEAM` (created by any existing account; deletable,
invitable, leaveable by anyone but its owner — unchanged from today's rules otherwise). Sign-up
(`identity-service`) still creates exactly one `PERSONAL` org per account, via `OrgProvisioned`, as
today. Every `TEAM` org after that is created directly by `org-team-service` (new: `POST
/org-team/orgs`, authenticated, no Keycloak call, no new credential — the caller already has one)
with the creator as its `OWNER`. Team-org creation stops being identity-service's job the same way
invite acceptance already isn't: `org-team-service` owns everything about membership beyond a
founding owner, and a second org for an existing person is a membership-shaped operation, not an
authentication-shaped one.

**3. `org_id` leaves the token.** The access token authenticates **who** (`sub`, standard identity
claims), never **which org**. This is the ADR-0003-breaking change both architecture documents
flagged in advance. Keycloak's `org_id` user attribute and its client-scope mapper are retired; no
replacement claim takes its place.

**4. Per-request org resolution replaces the tenant gate, everywhere a tenant-scoped resource is
served.** `org-team-service`'s "tenant gate" (path `orgId` == token `org_id`) is removed; the
"membership gate" it already has — resolve `orgId` from the path, read the caller's local
`(userId, orgId)` membership row, `404` if absent or the org is gone, `403` if present but the row
lacks the endpoint's required role — becomes the **entire** authorization decision. This is not a
new mechanism, only the removal of a check that assumed a token could address only one org. Any
future tenant-scoped service (`deploy-orchestrator-service`, `billing-service`, ...) adopts the
same shape from day one: resolve `orgId` from the path, verify it against a **local** membership
record built by consuming `org-team-service`'s membership events (`OrgMemberAdded`,
`OrgMemberRemoved`, `OrgMemberRoleChanged`) — never a synchronous call, and never trust `org_id`
off a token, because there no longer is one.

**5. Invite acceptance branches on whether the invited email already has an identity account.**
No existing account: unchanged — password in the body, `identity-service` creates the Keycloak
user, publishes `OrgInviteAccepted`. Existing account: no password taken; the caller authenticates
as themselves (a login step, not a switch), `identity-service` makes **no** Keycloak call at all,
and simply publishes `OrgInviteAccepted` carrying the existing `userId`.
`InviteAcceptanceService.handle()` in `org-team-service` needs no change for this case — it already
resolves purely from the invite row and the event's `userId`/`email`, indifferent to whether the
account is new or pre-existing.

**6. Keycloak stops holding any org-scoped state, so three of `identity-service`'s existing
listeners lose their Keycloak call.** Today, `OrgMemberRemovedListener` disables the whole Keycloak
account, `OrgMemberRoleChangedListener` rewrites a single realm-role assignment, and
`OrgDeletedListener` disables every account under the org — all three assume a token can carry
exactly one org's role, so "removed from your org" and "removed from your account" were the same
event. Under multi-org they are not: removing someone from one `TEAM` org must never touch their
ability to log in, since the same account may hold a personal org and other memberships untouched
by that removal. These three listeners' Keycloak Admin calls are retired; `org-team-service`'s
local membership row is the only place that fact needs to live, per decision 4. Nothing analogous
is needed for `OrgMemberAdded`, since it never had a Keycloak-side effect to begin with.
`OrgInviteRejectedListener` is unaffected: it only ever disables an account freshly created for a
now-repudiated invite, which is still exactly correct — that account's only reason to exist was
that invite.

**7. "Last active org" is a UI convenience, never an authorization input.** The dashboard may
remember which org a user last viewed (an `identity-service` profile field, or purely client-side
storage) to decide what to render on login, exactly as Vercel remembers a last-viewed team. It is
never consulted by any authorization decision; a stale or missing value only picks the wrong
*default screen*, never the wrong *access*.

## Consequences

**Easier**: a person invited into a second org needs no second account, no second email, and no
switch/reissue step to act on both; two browser tabs on two different orgs work simultaneously with
one login, matching Vercel/Supabase/GitHub; a demoted or removed member's access ends on the very
next request everywhere this pattern is adopted, not bounded by a token's lifetime — already true
inside `org-team-service`, now a platform-wide guarantee; login and token issuance in
`identity-service` get simpler, not harder — there is no org to resolve at all, only identity.

**Harder / new work this creates**:

- Every tenant-scoped service needs its own local membership read-model (consumed from
  `org-team-service`'s membership events) to check `(sub, orgId)` per request — the same
  no-synchronous-call discipline the platform already uses everywhere else, now a mandatory
  convention rather than an `org-team-service`-specific choice. Cheapest to establish now, while
  only `identity-service` and `org-team-service` exist; expensive to retrofit after a third and
  fourth service copy today's trust-the-token-claim pattern.
- `identity.users.org_id`, the Keycloak `org_id` user attribute, and its client-scope mapper need a
  migration: existing accounts keep their current value as their `PERSONAL` org id; the column's
  meaning narrows, it does not move.
- `org-team-service` gains a self-service org-creation path (`POST /org-team/orgs`) that does not
  originate from an `OrgProvisioned` event — the first mutating endpoint in this service that
  creates an `Organization` row outside a Kafka listener. It sources `email`/`displayName` from the
  token's own claims (already present on the resource-server's principal), not from a projection
  event, since the caller is already fully authenticated.
- `platform-common-security`'s `OrgContext` contract changes: `OrgContext.requireOrgId()` no longer
  has a token claim to read. Every call site across every service needs to move to
  path-plus-membership resolution; this is the single largest mechanical change this decision
  causes, tracked service by service as each is built or touched.
- The dashboard needs an org switcher backed by `GET /org-team/orgs` (new: "orgs I'm a member of",
  keyed off the caller's `sub`), since there is no token claim left to read a default org from.

**Not done by this decision**: fine-grained per-app or per-resource permissions narrower than an
org role (a real Vercel/Supabase feature — "access to only this app" — noted as a future extension
of `MembershipPolicy`, not designed here); account recovery/reactivation after removal (still
`identity-service`'s own open question, unrelated to this one); invite-signing key rotation.

## Alternatives considered

- **Session-scoped active org** (Slack/GitHub-CLI style: token carries one `org_id`, switching
  reissues it). Rejected: cannot deliver simultaneous multi-org access from two open sessions of
  the same browser without per-tab token isolation, which is not how the dashboard's session
  storage works, and the product requirement was explicitly framed as "no login step to act in an
  org I've been invited into" — a switch step, however cheap, is still a step.
- **Keep single-org-per-account; solve only the error message.** Rejected: it fails the actual
  requirement (a person genuinely needs one account across several orgs), not just its wording.
- **Put the full membership list inside the token** instead of removing `org_id` entirely.
  Rejected: swaps one staleness problem (wrong org) for another (stale membership list) and grows
  the token with every org a person joins, for no benefit over a request-time lookup the platform
  already pays for inside `org-team-service`.
- **A synchronous membership check from each service to `org-team-service`.** Rejected outright:
  the platform's one hard rule. The local-read-model pattern this ADR generalizes is what ADR-0016
  already chose for the same reason.
