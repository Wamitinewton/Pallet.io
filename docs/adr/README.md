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
