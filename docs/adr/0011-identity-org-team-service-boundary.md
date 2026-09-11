# 11. identity-service / org-team-service boundary, signed action tokens, single-org accounts

- Status: accepted
- Date: 2026-09-11

## Context

`docs/PROJECT.md`'s roadmap item 2 and `docs/workflows/ROADMAP.md` Phase 1d originally scoped
`identity-service` as a narrow producer: issue tokens, provision an org's identity on sign-up,
six checkpoints, no consumer side. `docs/identity-service/ARCHITECTURE.md` and
`docs/org-team-service/ARCHITECTURE.md` (written together, before either service's implementation
starts) expand that into a full design for both services and, in doing so, hit one boundary
problem that only has a coherent answer across both documents at once:

- Two services need to cooperate on one user-facing action ("accept this invite and get an
  account") without either calling the other synchronously — the platform's one hard rule
  (`docs/PROJECT.md`: "no service makes a blocking call to another").
- `org-team-service` owns *whether an invite is valid* (membership data, its aggregate).
  `identity-service` owns *the only credential that can create a Keycloak user* — nothing else in
  the platform gets that admin relationship, by design.
- Keycloak's own user-attribute model (ADR-0003: one `org_id` claim per token) already implies one
  organization per account, which is either confirmed as permanent or reopened as a real product
  requirement — not something to leave ambiguous while both services get built against it.

This is an architecture-changing decision (`CONTRIBUTING.md`: "a decision that changes
architecture gets an ADR in the same PR"), and both `ARCHITECTURE.md` documents call out, in their
own closing "Relationship to existing planning docs" sections, that they need this ADR before
either service's workflow files are written — the same rule ADR-0008 already modeled for
`notification-service`. Only `identity-service`'s workflow folder is being written against this
ADR right now (`org-team-service` stays Phase 2, per `docs/workflows/ROADMAP.md`); this ADR is
still shared because the boundary it records is symmetric and both documents assume it.

## Decision

Adopt the split, handoff mechanism, and account model both architecture documents design,
unchanged:

**1. AuthN/AuthZ-shaped split.** `identity-service` is the platform's only service with Keycloak
Admin credentials and the only service that ever writes a user's authentication state — who can
log in, and which organization founded their account. `org-team-service` owns everything about
what that person can do once they're in: organizations' display data, teams, apps, and every
membership beyond the founding owner (invites, roles, removals, ownership transfer). Neither
service duplicates the other's aggregate; `org_id` is the plain string both agree on the meaning
of, not a foreign key across their schemas.

**2. Signed action tokens as the sync-free handoff, scoped to invites only.**
`org-team-service` mints a self-contained JWS (HS256, via Nimbus — already on the classpath
transitively through the OAuth2 resource-server starter, no new dependency) carrying every claim
`identity-service` needs to act (`orgId`, `email`, `role`, `jti`, `exp`), signed with a shared
HMAC secret (`pallet.invites.signing-key`, sourced from the same env var in both services'
`config-repo/*.yml`, never hardcoded per `SECURITY.md`). `identity-service` verifies it locally —
signature and expiry only; single-use is enforced separately, by each side's own local replay
record (`identity-service`'s `consumed_invite_tokens`, keyed by `jti`). Both sides implement their
own small `SignedActionToken` class (`org-team-service`: issue-only; `identity-service`:
verify-only) rather than a new shared library module — the class is small enough, and a new
directly-shared module between exactly these two services would be a heavier coupling than the
shared secret it would replace. Password reset and email verification don't use this mechanism —
they're minted **and** verified by `identity-service` alone, so they use a cheaper opaque token
(random value, SHA-256 hash stored, never the raw token) instead.

**3. Single Keycloak account = single organization, permanently, for v1.** A person who is
genuinely part of two Pallet organizations gets two separate accounts (almost certainly two
different email addresses, since Keycloak and `identity.users.email` both enforce email
uniqueness per realm) rather than a GitHub/Slack-style multi-org-per-account model where a token's
active org is chosen per session. This isn't a new constraint — ADR-0003 already committed to one
`org_id` user attribute per account — this decision makes it an explicit, conscious one rather
than an accidental limitation discovered later.

## Consequences

**Easier**: invite acceptance is one HTTP call from the caller's perspective on either side, with
neither service's latency or availability depending on the other being up; a compromise of one
service's Keycloak credential surface doesn't also compromise the other's, because only
`identity-service` ever holds one.

**Harder / new work this creates**:

- `platform-common-events` gains seven new event types in `Topics`, specified in full in
  `docs/identity-service/ARCHITECTURE.md`'s Event contracts table and repeated from
  `org-team-service`'s side in its own: `OrgProvisioned`, `OrgInviteAccepted`,
  `UserProfileUpdated` (published by `identity-service`), `OrgMemberAdded`, `OrgMemberRemoved`
  (payload confirmed **unchanged** from what `notification-service` already assumed per
  ADR-0008), `OrgMemberRoleChanged`, `OrgDeleted` (published by `org-team-service`). This is a
  shared-module change needing its own review, same as `NotificationRequested`'s `audience` field
  was for ADR-0008.
- `notification-service` needs three new templates it doesn't have yet: `PASSWORD_RESET`,
  `EMAIL_VERIFICATION` (for `identity-service`), `ORG_INVITE` (for `org-team-service`) — flagged
  the same way `WELCOME` already exists.
- `identity-service` becomes a **consumer** for the first time — three `@KafkaListener`s
  (`OrgMemberRemoved`, `OrgMemberRoleChanged`, `OrgDeleted`) that each make a Keycloak Admin call
  before the message is considered handled. This is new for this service specifically, though not
  unprecedented in the repo (`notification-service`'s listener already does the analogous thing
  for SMTP).
- Those three listeners get zero real traffic until `org-team-service` ships (Phase 2) — the same
  "seam is real, the upstream producer isn't, yet" tradeoff ADR-0008 accepted for
  `notification-service`'s `ORG` broadcast, just with the roles reversed (the consumer ships
  first this time). Verified manually via a scratch producer per checkpoint, same as ADR-0008's
  precedent, not skipped.
- `docs/workflows/ROADMAP.md` Phase 1d's original six-row table is replaced with a pointer to
  `docs/workflows/identity-service/00-README.md`'s wider, eleven-checkpoint breakdown (done in
  this same change) — the same move ADR-0008 made for Phase 1c.
- A real, named consequence of no synchronous cross-service call: an invite accepted after its
  `orgId` was deleted, or accepted after being revoked one second earlier, can still succeed at
  the Keycloak-account-creation level with no matching `org-team-service` membership row. Both
  architecture documents name this in their own Failure modes sections rather than assume it away;
  mitigated by short invite TTLs, not eliminated.

**Not done by this decision**: MFA, SSO/SAML, personal access tokens (`identity-service`'s own
Extension points); a GDPR-style purge/account-recovery flow (Open questions in both documents);
rate limiting `/auth/login` and `/signup` (deferred to the identity-service hardening checkpoint,
to decide deliberately rather than default into); the asymmetric-signing alternative to the shared
HMAC secret (worth revisiting the day a third service needs to mint or verify one of these
tokens, not before). `org-team-service`'s own workflow folder is not written by this ADR — it
opens Phase 2 and gets its own workflow index when that phase starts.

## Alternatives considered

- **Multi-org-per-account from the start** (GitHub/Slack model). Rejected for v1: strictly more
  capable, but moving `org_id` off the Keycloak user attribute and resolving it at
  token-issuance/session time instead is a breaking change to ADR-0003, not an additive one —
  disproportionate to build before there's a real "one person, several orgs" requirement.
- **Resolve invite acceptance with a synchronous call between the two services**, either
  direction. Rejected outright — breaks the platform's no-blocking-cross-service-call rule and
  makes invite acceptance's latency and availability depend on two services instead of one.
- **Asymmetric (public/private key) token signing instead of a shared HMAC secret.** Removes the
  one piece of real coupling this design accepts (whoever rotates the secret rotates it in both
  services' config; a compromise of either service's config leaks it), at the cost of real
  key-management overhead. Deferred, not rejected — proportionate to revisit the day a third
  service needs to mint or verify one of these tokens, not before, since a symmetric secret
  between exactly two parties is proportionate for what it protects (an invite's org/email/role
  claims, not a bearer credential with account access).
