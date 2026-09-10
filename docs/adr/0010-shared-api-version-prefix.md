# 10. Shared, config-driven API version prefix

- Status: accepted
- Date: 2026-09-11

## Context

`notification-service`'s read API hardcodes its version in every endpoint:
`@RequestMapping("/api/v1/notifications")`. That pattern was about to get copied into every
future service's controllers verbatim, per `PROJECT.md`'s "API convention" plan and
`docs/notification-service/ARCHITECTURE.md`'s Read API section. A platform-wide version bump
(`v1` to `v2`) would then mean editing a `@RequestMapping` literal in every service that has one,
a mechanical, error-prone change with no single source of truth for what the current prefix even
is.

Two ways to centralize it were on the table:

- `server.servlet.context-path`, set once in `config-repo/application.yml`. Simplest possible
  change, but it prefixes the *entire* embedded servlet container, including actuator. Every
  service's `/actuator/health` and `/actuator/prometheus` would move to `/api/v1/actuator/...`,
  which breaks the assumption (already baked into `config-repo/application.yml`'s
  `management.endpoints.web.exposure` and every service's k8s/Prometheus wiring) that health and
  metrics live at a stable, unversioned path independent of the API's own version.
- A path prefix applied only to `@RestController`-mapped endpoints, leaving actuator (registered
  through its own `WebMvcEndpointHandlerMapping`, not `@RestController`) untouched.

## Decision

Add `pallet.api.prefix` (default `/api/v1`) as a `@ConfigurationProperties` value in
`platform-common-api`, read by a new `PalletApiAutoConfiguration` that registers a
`WebMvcConfigurer` calling `configurePathMatch().addPathPrefix(prefix,
HandlerTypePredicate.forAnnotation(RestController.class))`. Every service already depends on
`platform-common-api`, so this needs no new dependency to adopt.

A controller stops hardcoding the version:

```java
// before
@RequestMapping("/api/v1/notifications")

// after
@RequestMapping("/notifications")
```

The prefix itself lives in `config-repo/application.yml` (`pallet.api.prefix: /api/v1`), served to
every service by `config-server`. Bumping the platform's API version is changing that one value,
not touching a `@RequestMapping` in each service.

Actuator is unaffected by construction: `addPathPrefix`'s `HandlerTypePredicate` only matches
classes carrying `@RestController`, and actuator endpoints are never registered that way, so
`/actuator/health` and `/actuator/prometheus` stay exactly where every k8s probe, Prometheus scrape
config, and docker-compose healthcheck already expects them.

## Consequences

**Easier**: a version bump for every service's API is a one-line `config-repo` change instead of
a grep-and-replace across the codebase; a new service gets the prefix for free by depending on
`platform-common-api`, the same way it already gets `ApiResponse`/`PageResponse`.

**Harder / new work this creates**:

- `platform-common-api` now depends on `spring-boot-starter-webmvc` (optional, matching
  `platform-common-observability`'s `CorrelationIdFilter` precedent) and
  `spring-boot-autoconfigure`, so it is no longer a purely framework-agnostic DTO module. A
  non-HTTP consumer of `ApiResponse`/`PageQuery`/`PageResponse` (none exist yet) would pull in
  `spring-boot-autoconfigure` transitively even though it never needs the prefix behavior; the
  `@ConditionalOnWebApplication(type = SERVLET)` / `@ConditionalOnClass(WebMvcConfigurer.class)`
  guards mean no bean is actually created in that case, so this is a build-time cost, not a
  runtime one.
- This is a `platform-common` release, not a `notification-service` change: per `PACKAGES.md`,
  `notification-service` consumes `platform-common-*` as a pinned, published GitHub Packages
  version, not from local reactor source. `NotificationController`'s `@RequestMapping` cannot
  actually drop its `/api/v1` literal until a `platform-common` release ships this and
  `notification-service`'s `<platform-common.version>` is bumped to it, in a follow-up change.
  Until then, `pallet.api.prefix` sits in `config-repo/application.yml` unused, which is harmless.

## Alternatives considered

- **`server.servlet.context-path`.** Rejected: reprefixes actuator along with the API, breaking
  the unversioned health/metrics path every deployment surface already assumes. Fixing that would
  need a second decision (a separate `management.server.port`) that this change doesn't need to
  force.
- **A shared Java constant (`ApiPaths.V1`) instead of a config property.** Rejected: still needs a
  recompile-and-redeploy of every service to change, which is exactly the "one place" property the
  YAML-driven version avoids paying for a version bump.
