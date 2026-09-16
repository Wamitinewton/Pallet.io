# 15. Platform-wide session revocation via `RevokedSessionRegistry`

- Status: accepted
- Date: 2026-09-16

## Context

`identity-service` already exposes session revoke and revoke-other-sessions endpoints
(`UserService.revokeSession`/`revokeOtherSessions`), implemented by deleting the corresponding
Keycloak user session through the admin API.

That only closes half the gap. Deleting a Keycloak session stops Keycloak from issuing new tokens
under it, but does nothing to a token already handed out. Every Pallet service is a stateless
OAuth2 resource server (ADR-0003's single-realm model): it checks a JWT's signature, issuer, and
expiry locally and never calls back to Keycloak per request. So an access token minted moments
before a "log out everywhere" click stays valid for its full remaining lifetime, up to the realm's
15-minute `accessTokenLifespan` (`infra/keycloak/pallet-realm.json`), no matter what happened to
the Keycloak-side session.

That gap matters most for the case revoke-others exists to handle: a user who suspects a device is
compromised needs every other session's access to end now, not in up to 15 minutes. Fixing it needs
a check that runs on every request, in every service, without adding a synchronous call between two
Pallet services (AGENTS.md's service-boundary rule) and without making every service round-trip to
Keycloak per request, which would defeat the point of a stateless JWT and put every request's
availability behind Keycloak's own.

## Decision

**1. `RevokedSessionRegistry`**, a new interface in `platform-common-security`:
`revoke(sessionId, retention)` / `isRevoked(sessionId)`, keyed on the JWT's `sid` claim (the
Keycloak session id already present on every token).

**2. `SessionRevocationValidator`** implements `OAuth2TokenValidator<Jwt>` and checks the registry
on every JWT validation. It's contributed as a plain bean; Spring Boot's own
`JwtDecoderConfiguration` collects every `OAuth2TokenValidator<Jwt>` bean and composes it with the
standard timestamp/issuer checks automatically, so adopting this needs no `JwtDecoder` bean of its
own in any service. `platform-common-test`'s `KeycloakTestContainerConfiguration` needed a small
change to keep doing that same composition for its own test-only `JwtDecoder`, since that bean
already existed there and normally backs off Boot's auto-configuration.

**3. Two registry implementations**, chosen by what's on a service's classpath:

- `InMemoryRevokedSessionRegistry` (the default): a `ConcurrentHashMap` with a lazy expiry sweep.
  Fine for local dev and tests, wrong the moment a service scales past one replica, since a
  revocation written on one instance is invisible to the others. Logs a warning on startup so this
  doesn't fail silently.
- `RedisRevokedSessionRegistry`: a TTL'd key in Redis (`pallet:session:revoked:<sid>`), visible to
  every instance of every service sharing that Redis. It replaces the in-memory registry only when
  `spring-data-redis` is already on the service's classpath and a `StringRedisTemplate` bean
  exists. It fails open on a Redis error (logs, increments a
  `security.session_revocation.unavailable` counter, treats the token as not revoked) rather than
  taking every authenticated request down over a Redis blip, the same tradeoff
  `RedisTokenBucketRateLimiter` makes for gateway rate limiting.

**4. `identity-service`'s revoke and revoke-others flows** now write into the registry right after
deleting the Keycloak session, using a configurable retention
(`pallet.identity.session-revocation.retention`, default 15 minutes) that has to cover the realm's
`accessTokenLifespan` so the marker never expires before a token minted just before revocation
would have.

**5. `PalletAuthenticationEntryPoint`/`PalletAccessDeniedHandler`** were added alongside this. A
token rejected by `SessionRevocationValidator` (or any other resource-server check) is rejected
inside the security filter chain, before `DispatcherServlet` or `GlobalExceptionHandler` ever run. Without
a dedicated entry point and handler, that rejection would reach the client as a bare 401 or 403
with no body; both now render the same `ErrorResponse` shape every other error path in the
platform uses.

## Consequences

**Easier**: revoke and revoke-others now actually cut off access immediately, instead of only
stopping new token issuance. A stolen token becomes unusable within one registry write, not up to
15 minutes later. Every current and future service gets this by depending on
`platform-common-security`, the same way it already gets the resource-server baseline, with no
per-service code.

**Harder / new work this creates**: the Redis-backed registry only activates in a service that
already depends on `spring-data-redis`. `identity-service` and `api-gateway` do (the latter for
rate limiting already); `notification-service` and `config-server` don't, so today they'd fall back
to the in-memory registry and never see a revocation `identity-service` wrote to Redis. That's not
a bug in this decision so much as a known gap: a service that validates end-user tokens and doesn't
yet depend on `spring-data-redis` should add it before this protection actually applies to it. Worth
an audit once more services exist.

The retention window is also a manually maintained invariant. `pallet.identity.session-revocation.retention`
has to stay at or above the realm's `accessTokenLifespan`, and nothing enforces that relationship
today. Raising the token lifespan in Keycloak without a matching retention bump would quietly
reopen the exact gap this decision closes.

## Alternatives considered

- **Have every service call back to Keycloak, or to identity-service, to check session validity
  per request.** Rejected: that turns every authenticated request platform-wide into a synchronous
  call to another service, which is exactly what AGENTS.md's service-boundary rule and the
  platform's event-driven design rule out, and it ties every request's latency and availability to
  Keycloak or identity-service being up.
- **Shorten the realm's access token lifespan and rely on natural expiry instead of a registry.**
  Rejected: it still leaves a window, whatever the lifespan is, where a revoked session's token
  keeps working, and a shorter lifespan just means more refresh traffic platform-wide for a problem
  it shrinks rather than solves.
- **Ship only the in-memory registry and document the single-instance limitation.** Rejected:
  "revoke this session" wouldn't reliably work past one instance, which is the normal case for
  where this platform actually runs.
