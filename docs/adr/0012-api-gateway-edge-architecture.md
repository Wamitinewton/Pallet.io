# 12. api-gateway: servlet-stack Gateway, additive edge auth, no shared trust secret

- Status: accepted
- Date: 2026-09-13

## Context

`docs/PROJECT.md`'s roadmap item 2 puts `api-gateway` right after `identity-service` and before
`org-team-service`: "it needs a token issuer to route to, and building it early fixes the edge
auth and routing conventions before there are several services behind it." Four things need
deciding before a single route can be written, each with real consequences for every service that
lands behind the gateway afterward:

1. **Which Spring Cloud Gateway.** Spring Cloud 2025.1.2 ships two: the original WebFlux-based
   reactive gateway, and Gateway Server MVC — a servlet-based, blocking implementation built on
   `spring-boot-starter-webmvc`. Every other decision in this codebase already commits to the
   servlet stack (`AGENTS.md`: "modular starters, e.g. `spring-boot-starter-webmvc`, not `-web`"),
   and every `platform-common-*` module that would be useful at the edge —
   `PalletResourceServerAutoConfiguration` (`platform-common-security`), `CorrelationIdFilter`
   (`platform-common-observability`), `ExternalCall`/`ResilienceRegistries`
   (`platform-common-resilience`), `GlobalExceptionHandler`/`SecurityExceptionHandler`
   (`platform-common-exception`) — is a `jakarta.servlet.Filter` or a `@RestControllerAdvice`,
   neither of which runs on a WebFlux `WebFilter` chain without a reactive rewrite of each module.
2. **Does the gateway's JWT check replace each service's own, or run alongside it?** Every service
   built so far (`notification-service`, `identity-service`) is already an independent OAuth2
   resource server via `platform-common-security`. The gateway could become the *only* place a
   token is checked, with backends trusting a header the gateway stamps after verifying it — or it
   can be one more independent check in front of backends that keep verifying for themselves.
3. **How does the gateway find a backend instance?** `docs/PROJECT.md` already commits to "DNS
   based discovery throughout, Docker Compose's built-in DNS by service name during local
   development, then Kubernetes DNS once the platform runs on a cluster. No Eureka or other
   registry service in between" — this ADR doesn't reopen that, but it does have to say what a
   route's target URI actually resolves to **today**, since no service currently runs inside
   `docker-compose.yml` (`identity-service` and `notification-service` both run via
   `./mvnw spring-boot:run` on the host, per `AGENTS.md`'s local-endpoint table).
4. **Does edge rate limiting replace `identity-service`'s own limiter?** Checkpoint 12 of the
   identity-service workflow built `AuthRateLimiter` — a per-instance, in-memory,
   per-(client-address, endpoint) limiter — explicitly because "no gateway/WAF layer exists yet,"
   and named revisiting that decision "if `api-gateway` (Phase 2) makes a service-level limiter
   redundant" as an open question. This ADR has to answer that question, not leave it open a
   second time.

## Decision

**1. Gateway Server MVC, not the reactive gateway.** `services/api-gateway` is a
`spring-boot-starter-webmvc` application using `spring-cloud-starter-gateway-server-mvc`. This is
the one choice everything else in this ADR follows from: it means `api-gateway` depends on
`platform-common-security`, `-observability`, `-resilience`, and `-exception` exactly like every
other service, gets their autoconfiguration for free, and needs zero reactive equivalents built or
maintained. The tradeoff, named plainly: a blocking gateway ties up a servlet thread per in-flight
proxied request instead of a WebFlux gateway's non-blocking event loop, which matters at a
connection-volume this platform isn't near yet and can be revisited (Undertow/virtual threads
first, a reactive rewrite only if that's insufficient) the day it is.

**2. Edge auth is additive, never authoritative — no shared trust secret.** The gateway runs its
own `SecurityFilterChain` (overriding `PalletResourceServerAutoConfiguration`'s default, the same
override mechanism identity-service already uses for its own public endpoints) that validates the
same Keycloak-issued JWT against the same issuer every backend already checks, and forwards the
original `Authorization` header unmodified — it does not terminate the token, mint an internal
one, or stamp a trust header a backend is expected to believe instead of checking the token itself.
A request that reaches a backend directly (any local dev workflow that bypasses the gateway
entirely, or a future internal caller) is exactly as protected as one that came through the
gateway, because nothing downstream ever started trusting the gateway's say-so over its own check.
This costs a second JWT verification per request (cheap — local JWKS-cached signature check, no
network round trip) and buys running two independently-configured resource servers rather than one
gateway holding an implicit "everything behind me is a black box that trusts me" assumption — the
kind of assumption that turns one gateway misconfiguration into every service's auth bypass.

**3. Route targets are `@ConfigurationProperties`-bound URIs, env-var overridable, defaulting to
today's actual local topology.** Each route's backend URI comes from
`pallet.gateway.routes.<name>.uri`, sourced from `config-repo/api-gateway.yml`, e.g.
`IDENTITY_SERVICE_URI:http://localhost:8082` today. This is **not** a second service-discovery
mechanism competing with `docs/PROJECT.md`'s DNS commitment — it's the same env-var-driven URI
resolution every service already uses for its own dependencies (see
`config-repo/identity-service.yml`'s Keycloak/Postgres URLs), applied to routing. The value changes
in exactly one place, without new code, at each of the topology's three stages: `localhost:8082`
today (no service containers exist yet), `http://identity-service:8082` once services join
`docker-compose.yml`, `http://identity-service.pallet.svc.cluster.local` in the cluster. Naming it
here so the day services move into `docker-compose.yml`, updating `api-gateway`'s env vars is a
known, expected step, not a surprise.

**4. Edge rate limiting is coarse and additive, not a replacement for
`identity-service`'s own.** The gateway gets a Redis-backed, per-(subject-or-IP) token-bucket
limiter for generic abuse/burst protection across every route, and — per `docs/PROJECT.md`'s own
framing — is the eventual home for per-org, plan-based limiting once `org-team-service` and
billing exist. It does **not** replace `AuthRateLimiter`. That limiter defends a specific, narrow
threat model — OTP-guessing and credential-stuffing against four named auth endpoints, budgeted
per (client, endpoint) — that a coarse edge limiter sized for generic abuse is the wrong tool to
also cover precisely; collapsing the two into one budget would either make the edge limiter too
tight for ordinary multi-service traffic or too loose to actually bound OTP guesses. Both layers
stay, each doing the job it's actually shaped for — defense in depth, the same reasoning as
decision 2.

## Consequences

**Easier**: every `platform-common-*` module works at the edge unmodified; no reactive rewrite of
shared code, ever, for this decision to hold. A route to a new backend is a config change
(`pallet.gateway.routes.<name>`), not a code change. Moving a backend from `localhost` to a
container to a cluster is an env var change, not a redeploy of routing logic.

**Harder / new work this creates**:

- Two independent JWT checks per authenticated request (gateway, then backend) — a small,
  accepted latency cost, not a correctness gap; see decision 2.
- A blocking gateway means its own thread-pool sizing and per-route timeout budget matter more
  than they would on a reactive one — `docs/api-gateway/ARCHITECTURE.md`'s resilience section
  names the concrete defaults and where they're configured.
- Two rate-limiting layers to reason about instead of one — a caller hitting `/auth/email/verify`
  can be turned away by either, and they log differently; `docs/api-gateway/ARCHITECTURE.md`'s
  failure-modes table spells out which layer fires when.
- The `docker-compose.yml` env-var defaults **will** need to change the day any service actually
  joins that file as its own container — tracked as an open item in
  `docs/api-gateway/ARCHITECTURE.md`, not fixed by this ADR.

## Alternatives considered

- **Reactive Spring Cloud Gateway (WebFlux).** The "standard" choice for a gateway elsewhere, and
  strictly better at very high connection concurrency. Rejected for now: it would require either a
  second, reactive-flavored copy of `platform-common-security`/`-observability`/`-resilience`, or
  the gateway getting none of their autoconfiguration and re-solving JWT validation, correlation
  IDs, and circuit breakers from scratch — a real cost with no present traffic level that needs it.
- **Gateway-terminated auth with an internal trust header (`X-Pallet-Org-Id`, a signed
  gateway-to-service header, etc.).** Rejected: recreates exactly the shared-secret coupling
  `docs/identity-service/ARCHITECTURE.md`'s signed action tokens accept only between two services
  for one narrow handoff, but here between the gateway and *every* service, for *every* request —
  a much larger blast radius if that trust boundary is ever misconfigured or bypassed, for a
  performance saving (skipping one cheap local JWT verification) too small to justify it.
- **Retiring `AuthRateLimiter` once the gateway ships.** Rejected per decision 4 — different threat
  model, different budget shape; see above.
