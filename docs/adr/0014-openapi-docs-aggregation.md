# 14. OpenAPI documentation via `platform-common-openapi` and a gateway docs hub

- Status: accepted
- Date: 2026-09-14

## Context

OkoaTiko-Backend documents its API with springdoc (`springdoc-openapi-starter-webmvc-api`)
generating the spec, and a self-hosted, vendored `scalar.js` bundle rendering it as a static
`internal-docs/index.html`. That pattern works because OkoaTiko is one monolith: one
`openapi.json`, one page pointing at it.

Pallet is not one service. `docs/adr/0013-per-service-api-path-namespace.md` already gave every
service a fixed namespace under `/api/v1` (`identity-service` → `/api/v1/identity`,
`notification-service` → `/api/v1/notification`) and made `api-gateway` route each with a single
glob, resolved from `pallet.gateway.routes.<service>` (`config-repo/api-gateway.yml`). Copying
OkoaTiko's pattern verbatim — one `OpenApiConfig` and one vendored Scalar page per service — would
mean a developer opens `identity-service`'s docs at one URL and `notification-service`'s at
another, and every new service repeats the same `OpenApiConfig` boilerplate (bearer scheme, `Info`
block, error-schema wiring) that `platform-common-exception`/`-api` already centralized for
everything else those services return.

The goal, stated directly: a developer adds OpenAPI annotations to their controllers and gets a
documented API, visible from **one** URL for the whole platform, with no per-service Scalar/UI
code and no `api-gateway` change beyond what ADR-0013 already requires to make the service
reachable at all.

## Decision

**1. A new shared module, `platform-common-openapi`** (`io.pallet.common.openapi`), alongside the
existing `platform-common-api`/`-exception`/etc. It is auto-configuration only, the same shape as
every other `platform-common-*` module (`docs/workflows/README.md` §Conventions):

- Pins `springdoc-openapi-starter-webmvc-api` in `platform-common/pom.xml`'s
  `<dependencyManagement>` — one version, everywhere.
- `PalletOpenApiAutoConfiguration` registers the `bearerAuth` HTTP/JWT security scheme once
  (replacing every service's own copy of OkoaTiko's `OpenApiConfig` security-scheme block) and a
  default `OpenAPI` `Info` bean derived from `spring.application.name`, `@ConditionalOnMissingBean`
  so a service can still supply a richer one.
- An `OperationCustomizer` documents the error side of the response envelope automatically: for
  every operation, it adds the `ErrorResponse` schema (`platform-common-exception`) to the
  platform's standard status codes (400/401/403/404/409/429/500) unless the method already
  documents that code itself. This is the piece a per-service `OpenApiConfig` could never give for
  free — `ErrorResponse` is rendered by `GlobalExceptionHandler` from a thrown `AppException`,
  never visible in a controller method's return type, so springdoc has nothing to reflect it from
  without help.
- Deliberately **not** in scope: springdoc already reflects a controller's real generic return
  type (`ResponseEntity<ApiResponse<UserDto>>`) into the correct nested schema on its own — no
  customizer is needed to make the success envelope show up correctly.

**2. Each service opts in with a dependency and two config lines**, not code:

```yaml
# config-repo/<service>.yml
springdoc:
  api-docs:
    path: /api/v1/<namespace>/v3/api-docs   # <namespace> = the service's ADR-0013 segment
  swagger-ui:
    enabled: false                          # Scalar is the only UI; no service ships Swagger UI
```

The `api-docs.path` is placed **inside** the service's own ADR-0013 namespace on purpose, not
derived automatically from it — `pallet.gateway.routes.<service>.path` is itself explicit
per-service config, not derived from anything, and this follows the same convention rather than
inventing a second derivation mechanism for one config line. Putting the path inside the namespace
means it's already covered by the existing `/api/v1/<service>/**` gateway route
(ADR-0013) — **no `api-gateway` routing change for a service's docs to become reachable.**

**3. `api-gateway` hosts the single aggregation point**, not a new aggregator service. A small
controller builds a Scalar **multi-source** configuration (Scalar's API Reference natively
supports an array of `{url, title, slug}` sources, rendered as one page with a service picker) by
iterating the *same* `GatewayProperties.routes()` map that already drives request routing
(`docs/adr/0012-api-gateway-edge-architecture.md`, ADR-0013) — one source per registered service,
`url` = `/api/v1/<service>/v3/api-docs`. Served at `/docs`, publicly reachable (no auth gate) per
current product decision. `scalar.js` is vendored as a gateway static asset, matching OkoaTiko's
own choice and the project's general skepticism of unnecessary external runtime dependencies — see
`docs/workflows/api-gateway/07-docs-hub.md`.

A service becomes visible in the docs hub **the moment it's a `pallet.gateway.routes` entry** —
the same act that makes it reachable at all. There is no second "docs registry" to keep in sync
with the routing table.

## Consequences

**Easier**: adding documented endpoints to a service is "add the `platform-common-openapi`
dependency, annotate controllers with `@Tag`/`@Operation` as normal, two `config-repo` lines" —
matches the "just annotations" bar directly. The docs hub needs zero code change when a new
service is added; it inherits the routing table `api-gateway` already maintains. Specs stay live —
each tab in the hub reflects the actually-running service, not a generated snapshot that can drift.
The error-response documentation (400/401/etc. schemas) is correct everywhere by default instead
of copy-pasted per service.

**Harder / new work this creates**: each service's OpenAPI JSON is proxied live through the
gateway at request time — if a backend is down, that service's tab fails to load in the hub
(same failure mode as any other route through the gateway, not a new one). `platform-common-openapi`
is implicitly coupled to ADR-0013's namespace convention; a service that ever needs a different
`api-docs.path` shape still only edits one config line, not code. `config-server` and any future
non-tenant-facing internal service are not part of the hub by design (nothing in
`pallet.gateway.routes` for them) — this is a decision to record if one of them later grows a real
consumer-facing API worth documenting.

## Alternatives considered

- **A dedicated docs-aggregator service** that polls and merges every service's OpenAPI spec into
  one combined document. Rejected: real operational surface (its own deploy, its own staleness
  window, another thing that can be down) for a problem the gateway's existing routing table
  already solves for free — the same reasoning ADR-0013 used to reject host/port-based routing.
- **Build-time bundling into a static, published docs site** (Redocly/ReadMe-style). Rejected for
  now: it decouples the docs from what's actually running in a given environment, which is the
  opposite of what's useful while the API surface is still moving fast. Worth revisiting if/when
  Pallet ships a public, versioned, external-facing developer docs site — a different problem from
  "see every service's current API from one URL," which is what this ADR solves.
- **Each service keeps its own vendored Scalar page** (OkoaTiko's pattern, copied N times).
  Rejected: fails the stated goal directly — a developer would still open N URLs, and every service
  would carry the same duplicated `OpenApiConfig`/static-asset boilerplate
  `platform-common-openapi` exists to remove.
