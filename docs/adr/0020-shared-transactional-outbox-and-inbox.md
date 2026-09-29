# 20. Extract the transactional outbox and inbox into `platform-common`

- Status: accepted
- Date: 2026-09-28

## Context

ADR-0016 built the transactional outbox and inbox inside `org-team-service` and named the trigger
for extracting them: "when a second service needs it". ADR-0017 then fixed the relay's delivery
order (`(tx_id, id)` with the `pg_snapshot_xmin` visibility rule) and bounded its lock waits.
`git-integration-service` is the second service: every processed webhook, API mutation and
lifecycle change there must publish through an outbox, and every listener must use an inbox
(`docs/git-integration-service/ARCHITECTURE.md` §Changes this design needs elsewhere, item 3).

It also needs one thing `org-team-service` never did: a record key other than `orgId`. ADR-0019's
`org.membership.changed` is keyed by `orgId:userId`, and purging a deleted org publishes tombstones
to that compacted topic. A second hand-copied relay would drift from the first on exactly the
details (commit order, per-org blocking, broker-vs-row classification) that took two ADRs to get
right.

## Decision

1. **One module, `platform-common-outbox`, holds both outbox and inbox** (`io.pallet.common.outbox`,
   `io.pallet.common.inbox`). They are always adopted together: a service that needs atomic
   publishing needs effectively-once consumption for the same reasons.
2. **JDBC, not JPA.** The module uses `JdbcClient` and owns no `@Entity`, so a service needs no
   `@EntityScan` of a shared package and Hibernate's `validate` never sees the module's tables.
3. **Services own the DDL.** Each service's own Flyway migration creates `outbox_events` and
   `processed_events` in its schema, copied from the module's
   `META-INF/pallet/outbox/reference-schema.sql`. The module ships no Flyway migration: a shared
   one would need a version number that can't collide with every service's own sequence, and
   `org-team-service`'s tables already exist under its `V1`/`V3`. Drift is caught by
   `OutboxSchemaAssertions.assertMatchesReference(dataSource, schema)` in `platform-common-test`,
   which every adopting service calls from one test.
4. **`schema`, `metrics-prefix` and `advisory-lock-key` are required, with no default.** Two
   services on one Postgres cluster must never share a relay lock, and each keeps its own metric
   names: `org-team-service`'s existing `orgteam.outbox.*` dashboards and alerts don't change,
   and `git-integration-service` gets `git.outbox.*`. `schema` must be a plain lowercase
   identifier, because it is interpolated into the relay's SQL. The inbox duplicate counter uses
   the same prefix (`<prefix>.inbox.duplicates`).
5. **Explicit keys and tombstones are row data.** `record_key` (nullable) overrides the `orgId`
   key; a `tombstone` row has a key and no payload, and publishes a null value. A check constraint
   enforces that shape. `OutboxWriter.appendTombstone` refuses a topic that `Topics.isCompacted`
   does not mark, before writing anything. Ordering stays per `org_id` (the blocking clause is
   unchanged); the key only decides the Kafka partition. A tombstone row gets a generated
   `event_id` for the unique column and metrics; consumers never see it.
6. **The service declares what it publishes** as an `OutboxEventTypes` bean
   (`OutboxEventTypes.of(OrgMemberAdded.class, …)`), read from each record's `TYPE` constant once
   at startup; a missing or duplicate constant fails the context. The module has no built-in list;
   without a bean, the list is empty.
7. **Behavior is moved, not redesigned.** The relay's SQL, ordering, per-org blocking, lock
   timeouts, backoff and sensitive-payload scrubbing are byte-for-byte those of ADR-0016/0017, and
   `org-team-service`'s outbox, inbox and chaos tests moved into the module with the code. Two
   narrow additions:
   - `OutboxWriter.append` refuses an event type the service did not declare, so the mistake
     rolls back the business transaction instead of parking a row later.
   - The relay treats an `IllegalArgumentException` from the publisher as a row fault, so a
     hand-written tombstone for a non-compacted topic parks instead of backing off the relay.

## Consequences

- A service adopts the outbox by adding one dependency, copying the reference DDL into a
  migration, setting three properties and declaring its event types. It gets the relay, metrics,
  both health indicators (`outbox`, `kafkaProducer`), the inbox and the non-retryable-exception
  classification on the messaging module's error handler.
- The platform-common rule "don't add Postgres as a hard dependency of a shared module" is scoped
  to modules every service gets. This module is opt-in and exists only for services with a
  database.
- Retention sweeps stay in each service, next to its other sweeps and advisory-lock discipline.
  The module exposes `OutboxRepository.deletePublishedOlderThan` and
  `TransactionalInbox.deleteProcessedOlderThan` for them.
- Advisory lock keys, one per service (keep this table current):

  | Service | `pallet.outbox.advisory-lock-key` |
  |---|---|
  | `org-team-service` | `7305121408` |
  | `git-integration-service` | `7305121409` |

- `org-team-service` moves onto the module in its own checkpoint (`org-team-service/20`). It adds
  `record_key` and `tombstone` to its table in a new migration, and its chaos suite, now in the
  module, is the regression proof.
- Debezium/CDC in place of the polling relay stays deferred (ADR-0016); the module is the one
  place that change would now be made.
