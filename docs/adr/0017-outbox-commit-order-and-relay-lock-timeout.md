# 17. Outbox relay: commit-order delivery and bounded lock waits

- Status: accepted
- Date: 2026-09-21

## Context

ADR-0016 has the relay publish in `id` order, blocking an organization behind its earlier unpublished rows. Outbox ids
come from an identity column and are assigned at insert, not at commit. When transaction A appends row 1 and
transaction B appends row 2 and commits first, the relay sees only row 2, publishes it, and later publishes row 1: the
consumers see two events for one organization in the reverse of commit order. Chaos testing reproduced this
(`OrderingChaosIntegrationTest`). For `OrgMemberRemoved` followed by `OrgMemberAdded` the wrong order is a security
defect.

Separately, the relay holds a transaction (and the advisory lock) open while publishing, and commits each row's outcome
on a second connection. A schema change on `outbox_events` that queues for an `ACCESS EXCLUSIVE` lock while the relay is
mid-batch blocks the relay's second connection and, behind the queued lock request, every writer.

## Decision

1. `outbox_events.tx_id xid8 NOT NULL DEFAULT pg_current_xact_id()` records the writing transaction. The relay reads
   only rows with `tx_id < pg_snapshot_xmin(pg_current_snapshot())`, that is, rows from transactions older than every
   still-open one, and orders batches and the per-organization blocking clause by `(tx_id, id)`. Delivery order is
   therefore commit-safe.
2. The relay sets `lock_timeout` (`pallet.orgteam.outbox.lock-timeout`, default 5s) on its outer transaction and on
   each per-row transaction. A wait that exceeds it fails the poll, which is retried on the next tick. Migrations for
   this table set `lock_timeout` too.
3. `orgteam_outbox_held_back` counts pending rows withheld by an older open transaction; `OrgTeamOutboxHeldBack` alerts
   when it stays above zero.

## Consequences

- Per-organization order matches commit order without a second sequence or a table lock on write.
- Any long-running transaction anywhere in the database (not only in this service) delays publishing until it ends.
  That is the price of the guarantee; it is visible through the metric and alert, and the runbook says how to find it.
- Rows are held for as long as the oldest open transaction, so publish latency now includes writer transaction time.
- Not addressed: a relay that loses its advisory lock mid-batch still finishes the batch. Consumers' inbox absorbs the
  duplicates; revisit if the duplicate bound needs to tighten.

## Alternatives considered

- **Order by a per-org sequence taken under a row lock.** Serializes all writers of an organization; rejected.
- **Debezium/CDC.** Deferred as in ADR-0016; it reads the WAL in commit order but adds infrastructure.
- **Advisory-lock-per-relay-session redesign.** More work than a lock timeout for the same deploy hazard; deferred.
