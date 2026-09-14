# 13. Per-service API path namespace

- Status: accepted
- Date: 2026-09-13

## Context

`docs/api-gateway/ARCHITECTURE.md`'s routing model, as first designed, had each backend's route
enumerate every endpoint group it exposes — `identity-service`'s route needed
`paths: [/api/v1/signup, /api/v1/invites/**, /api/v1/auth/**, /api/v1/users/**]`, four separate
glob entries to route one backend. Two problems with that shape, both which get worse as more
services join the gateway:

1. **Every new endpoint group a backend adds needs a matching gateway config change.** A backend
   adding a new top-level resource (say, `identity-service` growing a `/mfa/**` group later, per
   `ARCHITECTURE.md`'s own Extension points) means editing `api-gateway`'s routing config in
   lockstep with a change that's otherwise entirely internal to that backend.
2. **Nothing prevents two services from choosing colliding path segments.** `identity-service` owns
   `/users/**` today; nothing stops a future service from also wanting a `/users/**`-shaped
   resource for an unrelated concept, at which point routing-by-bare-resource-path becomes
   ambiguous without a third disambiguating signal (a different gateway port, a different host, a
   priority order nobody remembers the reasoning for).

Both are solved the same way: give each service one fixed, permanent namespace segment under
`/api/v1`, and route on that segment alone.

## Decision

Every service's entire API is mounted under `/api/v1/<service>`, where `<service>` is that
service's name with the `-service` suffix dropped — `identity-service` → `/identity`,
`notification-service` → `/notification`. Concretely, in code, this is a class-level
`@RequestMapping("/<service>")` on every `@RestController` in the service (composing under
`platform-common-api`'s existing `pallet.api.prefix` auto-prefix from
[ADR-0010](0010-shared-api-version-prefix.md) — no change to that mechanism, this is one more
layer under it, not a replacement for it).

`api-gateway` then needs exactly **one** `pallet.gateway.routes.<service>` entry per backend,
routing on a single glob (`/api/v1/identity/**`) rather than one entry per endpoint group. Adding
an endpoint to an existing service's API — a new path under an already-registered namespace — is
purely that service's own change; it needs zero `api-gateway` config edit. Only a genuinely new
*service* needs a new gateway route.

This does not collapse `api-gateway`'s separate, per-path **public-vs-authenticated** allowlist
(`docs/api-gateway/ARCHITECTURE.md` §Edge authentication) into the same single glob — routing
(which backend) and authorization (does this specific path need a token) are different questions,
and only the first one benefits from being collapsed to one entry per service. A new *public*
endpoint under an existing service still needs its own `public-paths` entry at the gateway, exactly
as before; what this ADR removes is only the *routing* enumeration, never the auth one.

`notification-service`'s existing `/notifications` resource path (already matching its
`notifications` table/domain naming throughout `docs/notification-service/ARCHITECTURE.md`) nests
under the new `/notification` namespace segment unchanged: `/notification/notifications`. The
outer segment is the routing namespace; the inner one is that API's own resource name — the two
are independent, and the resource name wasn't renamed to avoid the segment repeating, since
renaming it would have desynchronized the path from the domain vocabulary the rest of that
document already uses.

## Consequences

**Easier**: `api-gateway`'s routing config is O(services), not O(endpoint groups) — a service can
grow its own API surface freely with no gateway-side change. Path collisions between services
become structurally impossible rather than a convention someone has to remember to check.

**Harder / new work this creates**:

- Every existing `identity-service` and `notification-service` endpoint's path changed
  (`/signup` → `/identity/signup`, `/auth/login` → `/identity/auth/login`, `/notifications` →
  `/notification/notifications`, etc.) — a breaking change to both services' API contracts. No
  external caller depends on the old paths yet (neither service has shipped past local
  development), so this lands with no deprecation window; a service that had already shipped to
  real callers would need one.
- `identity-service`'s own `SecurityConfiguration` (permitAll matcher list) and
  `RateLimitWebConfiguration` (rate-limited path list) both hardcode full paths built from
  `ApiPathProperties.prefix()`, independent of the `@RequestMapping`-driven routing — each literal
  string in both classes needed the same `/identity` segment inserted by hand; a future endpoint
  path change needs the same double-update discipline (the `@RequestMapping` and these two
  hardcoded lists don't derive from one shared source today, unlike `api-gateway`'s own
  `GatewayProperties`-derived allowlist, which is the more resilient shape). Worth a small
  follow-up to point these at a shared constant if a third such list ever appears.
- `notification-service`'s public path reads slightly redundant (`/notification/notifications`) —
  accepted rather than renamed, per the reasoning above.

## Alternatives considered

- **Host- or port-based routing per service** (`identity.pallet.local`, or a distinct gateway port
  per backend). Rejected: doesn't fit a single public URL/origin for the whole platform, and adds
  real operational surface (DNS entries or port mappings per service) for a problem a URL path
  segment already solves for free.
- **Leave routing enumerated per endpoint group, tolerate the collision risk.** Rejected: the
  maintenance cost compounds with every service added from here on — this is exactly the kind of
  convention worth fixing once, early, per `docs/PROJECT.md`'s own reasoning for building
  `api-gateway` this early ("fixes the edge auth and routing conventions before there are several
  services behind it").
