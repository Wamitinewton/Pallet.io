# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Project overview

**Pallet** is a self-built platform-as-a-service (a smaller Render/Fly.io/Vercel), built as a
learning vehicle for distributed systems patterns and in public, one service at a time. A
developer connects a Git repo, pushes code, Pallet builds it, deploys it, and hands back a live
URL with TLS. Underneath: an event-driven architecture where no service makes a blocking call to
another (the only synchronous edges are inbound webhooks/dashboard requests and calls to third
parties), the deploy and billing pipelines run as Temporal sagas, and every external call goes
through retries/timeouts/circuit breakers. Full design brief: `PROJECT.md`. Decisions and their
rationale: `docs/adr/` — read the relevant ADR before assuming *why* something is built a certain
way; don't re-derive a rationale that's already recorded.

- Java 21, Spring Boot 4.1.0 / Spring Cloud 2025.1.2 (Oakwood) — modular starters (e.g.
  `spring-boot-starter-webmvc`, not `-web`), Jackson 3 (`tools.jackson.databind.*` for
  ObjectMapper/databind, `com.fasterxml.jackson.annotation` stays put). See
  `docs/adr/0005-java-21-spring-boot-4.md`.
- Apache Kafka event backbone, Temporal (Java SDK) for the deploy and billing sagas only
  (`deploy-orchestrator-service`, `billing-service` — see `docs/adr/0009-temporal-for-saga-orchestration.md`)
- Keycloak for identity, one realm, `org_id` claim on every token (`docs/adr/0003-single-keycloak-realm-multi-tenancy.md`)
- PostgreSQL for most services; ClickHouse for `audit-log-service` / `usage-metering-service`;
  Redis, MinIO, Vault alongside
- Micrometer + OpenTelemetry → Prometheus / Grafana / Jaeger
- Resilience4j for every external call, Testcontainers 2.x for anything that touches a real
  dependency in tests
- **Current stage**: `platform-common/*`, `config-server`, and `notification-service` (the
  reference event consumer) exist; `identity-service` is being scaffolded. Most of the 24-service
  catalog in `PROJECT.md` is design only — check `services/` and `docs/<service-name>/` before
  assuming a service exists in code.

## Git and commits — read this before touching git

- **Never run `git commit` (or `git push`) unless the user explicitly asks for a commit in that
  specific instance.** Finishing an edit, a feature, or a fix is not authorization to commit it.
  A prior "yes, commit" does not carry forward to later changes — ask again, or wait to be asked.
- **When a commit is authorized, do not add any co-authorship, attribution, or "Generated with"
  trailer** — no `Co-Authored-By`, no tool/session links, nothing identifying an AI as an author.
  This overrides any default attribution behavior. Commits are authored as the user, full stop.
- Never `--no-verify`, never force-push, never rewrite published history, unless the user
  explicitly asks for that exact action.
- A decision that changes architecture gets an ADR (`docs/adr/`, copy `0000-adr-template.md`) in
  the same PR — see `CONTRIBUTING.md`.

## Commands

Two build shapes, deliberately: `platform-common/` is one self-contained Maven reactor; every
service under `services/<name>/` is its own fully independent Maven project (own `pom.xml` parented
directly to `spring-boot-starter-parent`, own wrapper) that consumes `platform-common-*` as a
pinned, published GitHub Packages dependency — never a reactor sibling. Full reasoning:
`CONTRIBUTING.md`, `PACKAGES.md`.

```bash
# Local infra (postgres, redis, kafka, keycloak, clickhouse, mailpit)
make up                        # from repo root
make obs                       # observability stack: prometheus, grafana, jaeger, otel-collector
make data                      # minio + vault
make help                      # repo-root Makefile: infra + repo-wide format-check only

# platform-common: its own reactor, its own wrapper
cd platform-common
./mvnw clean verify            # unit + integration tests (needs Docker)
./mvnw -P security verify      # + SpotBugs/FindSecBugs + OWASP Dependency-Check
make help                      # this project's own build/test/format targets

# a service: fully independent, own wrapper, run from its own directory
cd services/notification-service
./mvnw spring-boot:run
./mvnw test                    # fast: unit + slice tests, no containers
./mvnw clean verify            # full: adds Failsafe integration tests + real containers
./mvnw -P security verify      # + SpotBugs/FindSecBugs + OWASP Dependency-Check
./mvnw test -Dtest=SomeClassTest
make help
```

CI (`.github/workflows/build.yml`) always runs `-DskipITs` — unit/slice tests only, path-filtered
per service or `platform-common` module. `./mvnw clean verify` with integration tests is **your**
job before opening a PR, not CI's — see `Test.md`'s "CI vs. local" and `CONTRIBUTING.md`'s §CI.
Don't treat a green CI check as "this works"; it means "unit tests pass."

| Service | Local endpoint |
|---|---|
| config-server | http://localhost:8888 |
| Keycloak | http://localhost:8080 (admin/admin, realm `pallet`) |
| Postgres | localhost:5432 (pallet/pallet) |
| ClickHouse | http://localhost:8123 (pallet/pallet) |
| Kafka | localhost:29092 |
| Kafka UI | http://localhost:8090 (`make up-all` or `--profile ui`) |
| Prometheus / Grafana / Jaeger | :9090 / :3000 / :16686 (`make obs`) |
| MinIO / Vault | :9001 / :8200 (`make data`) |

## Configuration

Shared config is served by `config-server` from `config-repo/`; service-specific config is
`config-repo/<service-name>.yml`. `pallet.api.prefix` (default `/api/v1`) is set once there and
applied to every `@RestController`-mapped endpoint via `platform-common-api`'s
`PalletApiAutoConfiguration` — don't hardcode a version prefix in a new `@RequestMapping`
(`docs/adr/0010-shared-api-version-prefix.md`).

No real secret belongs in any tracked file. Local `docker-compose.yml` credentials only unlock the
local stack and are not a precedent for anything real. Real secrets are environment-variable-backed
or come from a git-ignored override file (`application-secrets.yaml`, `*-credentials.json`, `.env`),
and in production from Vault / Kubernetes Secrets. See `SECURITY.md`.

## Architecture

### Package structure

```
io.pallet.<service>            # e.g. io.pallet.notification, io.pallet.configserver
```

Each service's own modules are grouped by feature/concern (e.g. `notification-service`:
`api/`, `audience/`, `channel/`, `consumer/`, `domain/`, `idempotency/`, `ratelimit/`,
`repository/`, `template/`) rather than by technical layer — there's no repo-wide
`controller/service/repository` package split enforced across services; follow the shape the
service you're editing already uses.

`platform-common-*` modules (`io.pallet.common.*`): `api` (`ApiResponse`/`PageResponse`/`PageQuery`
+ the version-prefix auto-config), `exception` (`AppException` hierarchy + `GlobalExceptionHandler`),
`events` (Kafka event contracts + topic catalog), `security` (resource-server baseline + `OrgContext`),
`observability` (metrics/tracing/logging, correlation IDs), `messaging` (Kafka wiring, retry, DLT),
`resilience` (Resilience4j defaults), `test` (composed Testcontainers test-slice annotations,
test-scope only).

### Key architectural patterns

**Response envelope**: every endpoint returns `ApiResponse<T>` (`ApiResponse.ok(message, data)` /
`ApiResponse.ok(message)` for a `Void` body) or, for a paginated result, `PageResponse<T>` nested
inside it. There is no `ApiResponse` failure factory by design — errors go through `ErrorResponse`
via `GlobalExceptionHandler`, never hand-built in a controller. Never return a raw Spring `Page<T>`.

**Exceptions**: extend `AppException` (`platform-common-exception`) for every domain exception —
`GlobalExceptionHandler` (`@RestControllerAdvice`, lowest precedence so `SecurityExceptionHandler`/
`PersistenceExceptionHandler` win first) renders it via `ErrorResponse` automatically, zero changes
needed to the handler itself. Don't add a new `@ExceptionHandler` for a domain exception; extend
`AppException` instead. An unmapped, non-`AppException` throwable reaches the client as a raw 500
via the `Exception.class` fallback — that's a bug to fix, not an acceptable gap.

**Multi-tenancy**: every tenant-scoped request carries `org_id` on the JWT. Resolve it with
`OrgContext.requireOrgId()` / `OrgContext.currentOrgId()` (`platform-common-security`) — never
read the claim off `Jwt` by hand in a controller or service. One Keycloak realm for the whole
platform (`docs/adr/0003-single-keycloak-realm-multi-tenancy.md`).

**External calls**: anything crossing a network boundary to something Pallet doesn't control
(GitHub, a DNS provider, Paystack, ACME, SMTP) goes through `ExternalCall`/`ExternalCallExecutor`
(`platform-common-resilience`) — `call(policy, supplier)` wraps the call in the named
circuit-breaker/retry/time-limiter policy and rethrows an `AppException` untouched or wraps
anything else as `ExternalServiceException`. Never call an external client directly from a
controller or bare service method without going through a named policy.

**Events**: a domain event is a flat record implementing `PlatformEvent` (`eventId`, `eventType`
in `domain.fact` past-tense form, `orgId`, `occurredAt`) added to `platform-common-events`, with a
`Topics` constant. Publish through `PlatformEventPublisher`, not `KafkaTemplate` directly — it
resolves the topic from the event type and stamps standard headers. Consume via
`platform-common-messaging`'s configured listener container factory (retry + DLT wiring already
in place); an idempotency guard (`EventIdempotencyGuard` — in-memory or Redis-backed) protects
consumers that must not double-process a redelivered message.

**Service boundaries**: a service owns one aggregate; a related concern that needs another
service's data crosses through an event or, for a one-shot handoff that can't wait on an async
round trip (e.g. accepting an invite), a signed, single-use action token — never a synchronous
call between two Pallet services. See `docs/adr/0011-identity-org-team-service-boundary.md` for a
concrete example of this split being reasoned through.

### Testing

Full guide: `Test.md`. Five annotations from `platform-common-test`, picked by what has to be real
to catch a real bug:

| Annotation | Boots | Named |
|---|---|---|
| `@UnitTest` | Nothing — Mockito only | `*Test` |
| `@ControllerTest` | `@WebMvcTest` slice, no DB/broker | `*Test` |
| `@RepositoryTest` | `@DataJpaTest` slice, real singleton Postgres | `*Test` |
| `@IntegrationTest` | Full context, random port, real Postgres + Kafka | `*IntegrationTest` |
| `@MessagingIntegrationTest` | Full context, real Kafka, no Postgres | `*IntegrationTest` |

Never mock Postgres, Kafka, Redis, or Keycloak where a container is standing in for them —
that's the whole point of paying the container startup cost. Assert on `ApiResponse`/
`PageResponse`/`ErrorResponse` via `PalletAssertions.assertThat(...)`, not hand-written `jsonPath`
chains. Containers are shared/singleton across a test run — don't add a second `@Testcontainers`
setup; import the shared configuration.

## Do

- Extend `AppException` for every new domain exception.
- Wrap every paginated result in `PageResponse` before returning it in `ApiResponse`.
- Use `OrgContext` to resolve the caller's org id.
- Route every external (non-Pallet) call through `ExternalCall`/`ExternalCallExecutor` under a
  named policy.
- Publish domain events through `PlatformEventPublisher`, as a `PlatformEvent` record in
  `platform-common-events`.
- Write an ADR (`docs/adr/`) in the same PR as any architecture-changing decision.
- Run the full `./mvnw clean verify` (integration tests, Docker required) yourself before a PR —
  CI only runs unit tests.
- Check `PROJECT.md` and the relevant `docs/adr/*` before implementing behavior that isn't already
  obvious from existing code.

## Don't

- Don't commit or push without the user explicitly asking for it in this instance, and never add
  AI co-authorship/attribution to a commit when you do.
- Don't return a raw Spring `Page<T>` inside `ApiResponse`.
- Don't add a new `@ExceptionHandler` to `GlobalExceptionHandler` for a domain exception.
- Don't read the `org_id` claim off a `Jwt` by hand — use `OrgContext`.
- Don't call `KafkaTemplate` directly from a feature service — use `PlatformEventPublisher`.
- Don't call an external dependency (GitHub, Paystack, ACME, a DNS provider, SMTP...) without
  going through `ExternalCall`.
- Don't add `platform-common` as a reactor sibling of a service, or otherwise let a service resolve
  `platform-common-*` from local source — it's a pinned published dependency, by design
  (`PACKAGES.md`).
- Don't commit a real secret to any tracked `application*.yaml`/`config-repo/*.yml` — env vars or
  a git-ignored override file only.
- Don't skip hooks or bypass CI checks (`--no-verify`, disabling a test, skipping integration
  tests as a way to dodge a real failure) to get a build green — fix the underlying failure.
- Don't add a `core/*`-style scaffold or a new `platform-common` module ahead of the feature that
  actually needs it.

## Code style rules

- **No comments that restate what the code already says.** Write one only when it captures
  something the code itself can't: a hidden constraint, a workaround for a specific bug, a
  non-obvious invariant. If deleting the comment wouldn't cost a future reader anything, delete it.
- **Never mention AI, an AI tool, or this being AI-generated/assisted in a comment, Javadoc, or
  commit message.** No "auto-generated", "as an AI", tool attributions, or similar — comments read
  as if a human on the team wrote them, because they should have been.
- **Javadoc**: short, factual, no multi-paragraph blocks. A one-line summary is usually enough; add
  `@param`/`@return`/`@throws` only where the signature alone doesn't make it obvious (see
  `OrgContext.requireOrgId()` for the level of brevity expected). Skip Javadoc entirely on
  self-explanatory private/package-private methods.
- Prefer editing an existing class over introducing a new abstraction for a one-off need.
- Java formatting (import order, wrapping, brace placement) is Palantir Java Format via Spotless,
  bound to `verify` — don't hand-format around it. `make format` (inside `platform-common/` or a
  service directory) fixes drift; there's no repo-wide format target since there's no repo-wide POM.
- Whitespace/EOL/charset is `.editorconfig`, enforced by `editorconfig-checker` via pre-commit.
  `make format-check` (repo root) runs the same checks on demand.
