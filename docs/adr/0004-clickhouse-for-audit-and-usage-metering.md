# 4. ClickHouse for audit-log-service and usage-metering-service

- Status: accepted
- Date: 2026-09-07

## Context

Most Pallet services are a natural fit for Postgres. Two are not:

- `audit-log-service` consumes `audit.event.recorded` from every service and
  keeps an immutable record. The workload is append-only, write-heavy, and
  almost always queried over a time range.
- `usage-metering-service` meters compute time, bandwidth, and log retention per
  org. That is high-cardinality time-series data; Postgres would need heavy
  partitioning and rollup tables to keep up.

Postgres could carry both for a while, but the brief listed them as the likely
candidates to outgrow it, and switching a datastore later is expensive.

## Decision

Both services use ClickHouse from the start, not Postgres. ClickHouse is in the
local stack (`docker-compose.yml`, `core` profile) and in the Testcontainers set
(`testcontainers-clickhouse`).

## Consequences

- ClickHouse is a standing operational dependency from day one, not a later add.
- Because ClickHouse is already in the stack, the "operational weight" argument
  against also using it as the `log-service` store (still an open question,
  ADR pending) is weaker than it first looked.
- These two services do not participate in the Postgres "database strategy"
  decision (ADR-0007).
