# 19. Membership state topic for local read models

- Status: accepted
- Date: 2026-09-28

## Context

ADR-0018 took `org_id` off the token. Every tenant-scoped service now authorizes a request against
a local membership read model: a primary-key read of `(orgId, userId)` that says whether the caller
is a member and with which role. ADR-0016 forbids a synchronous call to `org-team-service` to
answer that question, so the read model has to be built from events.

The events that exist today are incremental: `org.member.added`, `org.member.removed` and
`org.member.role.changed`, on three topics. They don't work for this:

- **Order.** Kafka orders records within one partition of one topic, never across topics. A read
  model built from three topics can apply a role change before the add it follows, or an add after
  the removal that ended it, and the result depends on consumer timing.
- **Seeding.** A service deployed after the fact, or one whose read model is rebuilt, needs every
  current membership. Delete-policy topics have already expired the history that would replay it,
  and replaying a full history to derive current state is the wrong shape anyway.

`git-integration-service` is the first service after `org-team-service` that needs this read model.
Every later tenant-scoped service needs the same thing, so the decision is platform policy, not a
detail of one service.

## Decision

**1. One compacted topic carries the full current state of every membership.**
`org.membership.changed` (`Topics.ORG_MEMBERSHIP_CHANGED`) is created with
`cleanup.policy=compact`. Each record is an `OrgMembershipChanged`: the standard envelope plus
`userId`, `role` (lowercase Keycloak name), `status` (`ACTIVE` or `REMOVED`) and `membershipVersion`.
Records are keyed `orgId:userId` (`OrgMembershipChanged.key`), so all states of one membership land
in one partition in order, and compaction keeps the latest one indefinitely. `org-team-service`
appends one to its outbox in the same transaction as every membership change.

**2. Removal is a `REMOVED` record, not a tombstone.** A tombstone disappears once compaction runs,
after which a consumer seeding from the beginning can't tell a removed member (`403 NOT_A_MEMBER`)
from a stranger to an org it has never heard of (`404 ORG_NOT_FOUND`). Tombstones are published only
when `org-team-service` purges a deleted org's data, when forgetting the membership is the point,
and when its retention sweep deletes a `REMOVED` membership row (365 days after removal). The second
case is forced, not chosen: a membership created again after its row is gone starts at version 0, and
a consumer still holding the old `REMOVED` record at a higher version would drop every record of it,
locking a re-invited member out of every service that reads this topic.
`PlatformEventPublisher.publishTombstone` refuses any topic the catalog does not mark compacted.

**3. Consumers apply a record only when its version is newer.** A consumer stores the applied
`membershipVersion` as `source_version` and applies a record only when `membershipVersion` is
greater. A replay from offset zero, a redelivery, or a record that arrives late then never moves a
membership backwards. Together with the transactional inbox this makes the projection idempotent
and convergent.

**4. The catalog decides cleanup policy.** `Topics.isCompacted` is the single place a topic is
marked compacted, next to its name; `platform-common-messaging` creates marked topics compacted with
`min.compaction.lag.ms` from `pallet.messaging.compaction.min-lag` (default one hour, so a consumer
that is briefly behind still sees intermediate states). A compacted topic's `.DLT` stays
delete-policy: compacting it would keep only the latest poison record per key.

**5. The incremental topics stay.** `org.member.*` keep their existing consumers (`identity-service`,
`notification-service`). New consumers that need current membership state read
`org.membership.changed` instead.

## Consequences

**Easier**: any new tenant-scoped service seeds its authorization read model by consuming one topic
from the beginning, with no call to `org-team-service`, no ordering logic, and no dependency on how
long the incremental topics retain history. Rebuilding a corrupted read model is a consumer-offset
reset.

**Harder**:

- `org-team-service` publishes every membership change twice, once incrementally and once as state,
  and the two can't drift. Both come from the same outbox transaction, so they commit together.
- Records on this topic are keyed by membership, not by `orgId`. `PlatformEventPublisher` gains a
  `publish(event, key)` overload and the outbox gains an explicit record-key column
  (`platform-common/09`). A blank key is rejected: it would fall back to Kafka's round-robin
  partitioner and lose per-membership order.
- A consumer lags `org-team-service` by the outbox poll interval plus consumer lag. A new member
  may be refused, and a removed member keep access, for that window. It is the price of ADR-0018's
  no-synchronous-call rule and is measured from each record's `occurredAt`.
- The topic grows with the number of memberships ever created, bounded by compaction to one record
  per membership plus purged-org tombstones until `delete.retention.ms` passes.
- `KafkaAdmin` never alters the config of an existing topic. An environment where
  `org.membership.changed` already existed with delete policy would need a one-off
  `kafka-configs --alter --add-config cleanup.policy=compact`. None does today: nothing referenced
  the topic before this change. Changing `min-lag` later has the same caveat.

**Follow-up**: `org-team-service/21-membership-state-topic.md` publishes the records and backfills
existing memberships; `git-integration-service/03` is the first consumer.

## Alternatives considered

- **Seed by a synchronous call to `org-team-service`.** Forbidden by ADR-0016 and ADR-0018, and it
  makes every consumer's startup depend on another service's availability.
- **A snapshot-on-request event** (a consumer asks, `org-team-service` replies with a snapshot).
  Another round trip, and a race between the snapshot and the incremental events published while
  it is in flight.
- **Keep consuming `org.member.*` and reorder in the consumer.** Impossible without a sequence
  shared across topics, which the incremental events don't carry, and it still doesn't solve
  seeding.
- **Tombstones for removal.** Rejected in decision 2: compaction erases the difference between
  "removed" and "never a member".
