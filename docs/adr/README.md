# Architecture Decision Records

Point-in-time decisions and their rationale. `PROJECT.md` is the living
description of the system; these capture *why* a choice was made so it is not
silently reversed later. See [0001](0001-record-architecture-decisions.md).

| # | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | accepted |
| [0002](0002-kubernetes-as-workload-orchestrator.md) | Kubernetes as the workload orchestrator | accepted |
| [0003](0003-single-keycloak-realm-multi-tenancy.md) | One Keycloak realm, `org_id` claim on every token | accepted |
| [0004](0004-clickhouse-for-audit-and-usage-metering.md) | ClickHouse for audit-log-service and usage-metering-service | accepted |
| [0005](0005-java-21-spring-boot-4.md) | Java 21 and Spring Boot 4.1 | accepted |
| [0006](0006-event-schema-format.md) | Plain JSON event payloads to start | accepted |
| [0007](0007-local-database-strategy.md) | Shared Postgres, schema per service (local) | accepted |
| [0008](0008-notification-service-foundation-scope.md) | notification-service foundation ships in-app + broadcast, not email-only | accepted |
| [0009](0009-temporal-for-saga-orchestration.md) | Temporal for saga orchestration and durable workflow execution | accepted |
| [0010](0010-shared-api-version-prefix.md) | Shared, config-driven API version prefix (`platform-common-api`) | accepted |
| [0011](0011-identity-org-team-service-boundary.md) | identity-service / org-team-service boundary, signed action tokens, single-org accounts | accepted |
| [0012](0012-api-gateway-edge-architecture.md) | api-gateway: servlet-stack Gateway Server MVC, additive edge auth, no shared trust secret | accepted |
| [0013](0013-per-service-api-path-namespace.md) | Per-service API path namespace (`/api/v1/<service>/**`), one gateway route per backend | accepted |
| [0014](0014-openapi-docs-aggregation.md) | OpenAPI documentation via `platform-common-openapi` and a gateway docs hub | accepted |
| [0015](0015-session-revocation.md) | Platform-wide session revocation via `RevokedSessionRegistry` | accepted |
| [0016](0016-org-team-service-production-design.md) | org-team-service: transactional outbox and inbox, local-row authorization, invite-rejection compensation | accepted |
| [0017](0017-outbox-commit-order-and-relay-lock-timeout.md) | Outbox relay: commit-order delivery and bounded lock waits | accepted |
| [0018](0018-multi-org-per-account-and-per-request-authorization.md) | Multi-org-per-account and per-request org authorization | accepted |
| [0019](0019-membership-state-topic.md) | Compacted `org.membership.changed` topic for local membership read models | accepted |
| [0020](0020-shared-transactional-outbox-and-inbox.md) | Transactional outbox and inbox extracted into `platform-common-outbox` | accepted |
| [0021](0021-github-app-source-host-integration.md) | GitHub App (not OAuth App) as Pallet's source-host integration, shared installations, per-repository verification | accepted |
| [0022](0022-durable-webhook-intake-with-active-recovery.md) | Durable webhook intake: persist-then-acknowledge in Postgres, the chain rule for order, active recovery because GitHub never redelivers | accepted |
| [0023](0023-web-app-architecture.md) | Web app: one Next.js deployable as dashboard and BFF, clean architecture enforced by lint, TanStack Query as the only server-state cache | accepted |
| [0024](0024-edge-rate-limiting-token-bucket.md) | Edge rate limiting as a Redis token bucket: Redis-clock refill, atomic Lua script, `Retry-After` on every 429 | accepted |
| [0025](0025-web-bff-session.md) | Web BFF session: tokens in encrypted Redis behind an opaque httpOnly cookie, single-flight refresh, `/bff` allow-list proxy with origin-checked writes | accepted |
| [0026](0026-tenant-runtime-sandboxed-kubernetes-firecracker-deferred.md) | Tenant runtime: sandboxed `RuntimeClass` (gVisor default, Kata + Firecracker tier) behind a `WorkloadRuntime` port, self-managed Firecracker runtime deferred | proposed |

## Still open (from `PROJECT.md`)

- Runtime isolation for tenant workloads: gVisor vs Kata Containers
- Cluster provisioning model: static Terraform vs Cluster API
- Log store: Loki vs ClickHouse
- First DNS provider integration (Cloudflare is the likely start)
- Deployed database strategy (ADR-0007 covers local only)
- Network isolation between tenants and control plane: `NetworkPolicy` vs service mesh + mTLS
- Temporal visibility store: Postgres (default, per ADR-0009) vs Elasticsearch once
  search-by-custom-attribute needs grow
- Temporal worker topology: embedded in the service process (default, per ADR-0009) vs a
  dedicated worker-only deployment of the same JAR, if worker load ever needs to scale
  independently of request-serving load
