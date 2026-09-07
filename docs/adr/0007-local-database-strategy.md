# 7. Shared Postgres, schema per service (local); revisit for deployed environments

- Status: accepted
- Date: 2026-09-07

## Context

The brief leaves the Postgres topology open: one instance per service (true
isolation, heavier to run) vs a shared cluster with a schema per service
(simpler, weaker isolation). This only concerns the Postgres-backed services —
`audit-log-service` and `usage-metering-service` are on ClickHouse (ADR-0004).

## Decision

For **local development and CI**: one shared Postgres, a `pallet` database, one
schema per service, plus a separate `keycloak` database (Keycloak owns its
whole schema). See `deploy/local/postgres/init-databases.sql`.

For **deployed environments**: deferred. The likely answer is a managed Postgres
per service (or per bounded group) once the platform runs for real, decided in a
follow-up ADR when there is a deployment target to decide against.

## Consequences

- Local footprint stays small — one Postgres container.
- Services must not reach across schemas; each owns its own and talks to others
  through APIs or events, so the later split to separate instances stays cheap.
- Integration tests use a Testcontainers Postgres per service, matching the
  deployed direction rather than the shared local one.
