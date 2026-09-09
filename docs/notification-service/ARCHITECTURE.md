# notification-service — Architecture

Status: proposed (pre-implementation design). Extends the sketch in `PROJECT.md`'s
`notification-service` paragraph and `docs/workflows/ROADMAP.md` Phase 1c with a second channel
(in-app) and the read-side API that channel needs. See **Relationship to existing planning docs**
at the end before starting Sprint 1 — this doc adds scope that Phase 1c's table doesn't cover yet
and should get a short ADR when implementation starts, per `CONTRIBUTING.md`'s "a decision that
changes architecture gets an ADR in the same PR."

## Contents

- [Purpose](#purpose)
- [Position in the system](#position-in-the-system)
- [Design goals and non-goals](#design-goals-and-non-goals)
- [Domain model](#domain-model)
- [Data model](#data-model)
- [Inbound contract: `notification.requested`](#inbound-contract-notificationrequested)
- [Channel abstraction and the template registry](#channel-abstraction-and-the-template-registry)
- [Broadcast notifications and future org-team-service integration](#broadcast-notifications-and-future-org-team-service-integration)
- [Rate limiting per organization](#rate-limiting-per-organization)
- [Processing pipeline](#processing-pipeline)
- [Read API: in-app notifications](#read-api-in-app-notifications)
- [Distributed systems mechanisms](#distributed-systems-mechanisms)
- [Delivery state machine](#delivery-state-machine)
- [Failure modes](#failure-modes)
- [Component view](#component-view)
- [Deployment and scaling view](#deployment-and-scaling-view)
- [Package layout and dependencies](#package-layout-and-dependencies)
- [Extension points](#extension-points)
- [Open questions / deferred](#open-questions--deferred)
- [Relationship to existing planning docs](#relationship-to-existing-planning-docs)

## Purpose

`notification-service` is the first feature service and the reference event consumer every later
service copies (`PROJECT.md`). Its job: any service publishes one `notification.requested` event
when it wants a tenant told something; this service renders it and delivers it on whatever
channels that notification type is configured for, records what happened, and — for channels with
no external inbox of their own — lets the frontend fetch what it delivered.

Two channels ship in this foundation:

- **Email** — fire-and-forget delivery through an SMTP relay (Mailpit locally). Nothing to query
  back; success means the message left the building.
- **In-app** — there is no external inbox, so *delivery is persistence*. The row this service
  writes on delivery is the same row a `GET /api/v1/notifications` call returns later, which is
  why in-app is architected as a first-class channel now rather than bolted on when a dashboard
  needs it.

Everything else — Slack, tenant webhooks, SMS — plugs into the same `NotificationChannel`
abstraction later without touching the consumer, the idempotency guard, or the read API.

## Position in the system

```mermaid
flowchart LR
    subgraph Producers
        P1[identity-service]
        P2[billing-service]
        P3[deploy-orchestrator-service]
        P4[any future producer]
    end

    subgraph Backbone["Event backbone (Kafka)"]
        T1[["notification.requested"]]
        T2[["notification.requested.DLT"]]
    end

    subgraph NS["notification-service"]
        C[Consumer:<br/>NotificationRequestedListener]
        R[Render + channel fan-out]
        API[Read API:<br/>NotificationController]
    end

    DB[(Postgres<br/>schema: notification)]
    SMTP[SMTP relay<br/>Mailpit local, real provider later]
    GW[api-gateway]
    FE[Tenant dashboard]

    P1 -- publish --> T1
    P2 -- publish --> T1
    P3 -- publish --> T1
    P4 -- publish --> T1
    T1 --> C
    C --> R
    R -- retries exhausted / bad payload --> T2
    R -- EMAIL channel --> SMTP
    R -- IN_APP channel --> DB
    C -. idempotency check .-> DB

    FE -- JWT --> GW --> API --> DB
```

Nothing points back into a producer. The only synchronous edges this service has are its own
inbound REST calls (dashboard → gateway → this service) and its own outbound SMTP call — both are
either behind auth or behind `platform-common-resilience`, per `PROJECT.md`'s "no service makes a
blocking call to another" rule. Producers never learn whether a notification actually sent;
that is this service's problem to solve (or expose via metrics/audit), not theirs to wait on.

## Design goals and non-goals

**Goals**

- Adding a notification type is a producer-side change (publish the event) plus one template file
  here — no schema change, no new topic, no new consumer.
- Adding a channel is a new `NotificationChannel` implementation plus a Spring bean — the
  consumer, idempotency guard, and read API are channel-agnostic.
- A redelivered event, or two logically-duplicate requests for the same fact, produce one
  notification per channel, not two.
- Every consume → render → send hop is one trace; sent/failed/retried/dead-lettered/read counters
  are exported from the first commit, not bolted on later.
- The service degrades gracefully under a slow or dead SMTP host: the consumer keeps making
  progress on other channels/messages instead of stalling.
- **Broadcast (org-wide) is a first-class audience from day one**, not retrofitted when
  `org-team-service` ships. The data model, fan-out logic, and a membership read-model seam are
  built now; the only thing missing until Phase 1d/2 is a real producer of membership events to
  populate that seam. See [Broadcast notifications](#broadcast-notifications-and-future-org-team-service-integration).
- **A noisy org cannot degrade delivery for other tenants.** Per-org volume is bounded without
  ever blocking a Kafka partition's poll loop — see [Rate limiting](#rate-limiting-per-organization).
- **Every notification is retained in full** — no TTL, no purge job, no silent data loss on old
  history. Retention is a deliberate product decision here, not an oversight; see
  [Data model](#data-model).

**Non-goals (this foundation)**

- Real-time push (WebSocket/SSE) for in-app notifications — decided, but deliberately deferred:
  the read API is pull-based for now, and the data model is shaped so a push layer bolts on later
  without a migration. See [Open questions](#open-questions--deferred).
- Per-user notification preferences / opt-out / digesting — the template registry decides
  channels for now, not a per-recipient setting.
- A schema registry or Avro/Protobuf — ADR-0006 keeps the wire format plain JSON.

## Domain model

Two concepts, deliberately kept apart because they answer different questions:

- **Notification** — the *content*: what was decided, rendered once from a template and a
  variable map. It answers "what happened and what does it say." Immutable after creation — a
  later template edit never rewrites history.
- **NotificationDelivery** — the *attempt*: one row per (notification, channel), answering "did it
  reach the recipient on this channel, and what's its state now." Email's delivery row is
  write-once (`SENT`/`FAILED`); in-app's delivery row is also the read-side record (`read_at`).

Splitting them is what lets one notification fan out to N channels without duplicating rendered
content N times, and lets the read API stay ignorant of anything email-specific.

```mermaid
classDiagram
    class Notification {
        UUID id
        String orgId
        String notificationType
        UUID sourceEventId
        String dedupeKey
        Audience audience
        String renderedTitle
        String renderedBody
        Map~String,Object~ variables
        Instant createdAt
    }

    class NotificationDelivery {
        UUID id
        UUID notificationId
        Channel channel
        String recipient
        DeliveryStatus status
        int attemptCount
        String lastError
        Instant sentAt
        Instant readAt
        Instant createdAt
        Instant updatedAt
    }

    class Audience {
        <<enumeration>>
        SINGLE
        ORG
    }

    class Channel {
        <<enumeration>>
        EMAIL
        IN_APP
    }

    class DeliveryStatus {
        <<enumeration>>
        PENDING
        THROTTLED
        SENT
        FAILED
        DEAD_LETTERED
    }

    Notification "1" --> "*" NotificationDelivery : fans out to
    Notification --> Audience
    NotificationDelivery --> Channel
    NotificationDelivery --> DeliveryStatus
```

`audience` is stamped once on the `Notification` from the inbound event and never changes — it
records *what was asked for* (one person, or the whole org), independent of how many
`NotificationDelivery` rows that produced. `SINGLE` always produces one delivery per selected
channel; `ORG` produces one delivery per *(resolved member, selected channel)* pair — see
[Broadcast notifications](#broadcast-notifications-and-future-org-team-service-integration).
`THROTTLED` is a transient status introduced by per-org rate limiting — see
[Rate limiting](#rate-limiting-per-organization).

## Data model

One Postgres schema, `notification` (shared cluster, schema-per-service, per ADR-0007), owned
exclusively by this service — no other service reads or writes it directly.

```mermaid
erDiagram
    NOTIFICATIONS ||--o{ NOTIFICATION_DELIVERIES : "fans out to"
    NOTIFICATIONS {
        uuid id PK
        varchar org_id
        varchar notification_type
        uuid source_event_id UK "idempotency key"
        varchar dedupe_key
        varchar audience "SINGLE | ORG"
        varchar rendered_title
        text rendered_body
        jsonb variables
        timestamptz created_at
    }
    NOTIFICATION_DELIVERIES {
        uuid id PK
        uuid notification_id FK
        varchar channel
        varchar recipient
        varchar status
        int attempt_count
        text last_error
        timestamptz sent_at
        timestamptz read_at
        timestamptz created_at
        timestamptz updated_at
    }
    ORG_MEMBERS {
        varchar org_id PK
        varchar user_id PK
        varchar email
        varchar status "ACTIVE | REMOVED"
        timestamptz updated_at
    }
```

`ORG_MEMBERS` has no foreign key to `NOTIFICATIONS` — it's an independent local read-model, not
part of the notification/delivery aggregate. See
[Broadcast notifications](#broadcast-notifications-and-future-org-team-service-integration) for
what populates it and why it lives in this schema at all.

Indexes that matter from day one (Flyway, not an afterthought migration):

- `notifications(source_event_id)` **unique** — this *is* the event-idempotency guard (see below);
  a redelivery hits a constraint violation, which the guard treats as "already handled."
- `notifications(org_id, dedupe_key)` unique **partial** index (`WHERE dedupe_key IS NOT NULL`) —
  collapses logically-duplicate requests from two different producers.
- `notification_deliveries(notification_id, channel, recipient)` **unique** — one delivery row per
  *(notification, channel, recipient)* triple. `recipient` is part of the key, not just
  `(notification_id, channel)`, because a broadcast fans one notification out to many recipients
  on the same channel; fan-out is `INSERT ... ON CONFLICT DO NOTHING`, so a retried fan-out step
  (or a partially-completed broadcast resumed after a crash) never double-delivers a
  member/channel pair that already succeeded.
- `notification_deliveries(recipient, channel, created_at DESC) WHERE channel = 'IN_APP'` — the
  index the read API's list query actually runs against.
- `notification_deliveries(recipient, channel) WHERE channel = 'IN_APP' AND read_at IS NULL` —
  backs the unread-count endpoint without a full table scan per request.
- `notification_deliveries(status, created_at) WHERE status = 'THROTTLED'` — the index the
  delivery-retry scheduler sweeps (see [Rate limiting](#rate-limiting-per-organization)).
- `org_members(org_id, status)` — the membership-resolution query for an `ORG` broadcast.

`recipient` is channel-appropriate: an email address for `EMAIL`, a user id (the token's `sub`)
for `IN_APP`. Storing it on the delivery row, not the notification row, is what lets one
notification address different identifiers per channel, and what lets a broadcast address a
different recipient per row while sharing one rendered `Notification`.

**Retention.** Nothing here deletes a row. `notifications` and `notification_deliveries` are
retained in full — no TTL, no archival job, no purge. Unread state is a column
(`read_at IS NULL`), never a reason to remove a row. This is a product decision, not a gap: a
tenant's notification history is part of their record. The only operational consequence worth
planning for as volume grows is query performance, not storage correctness — if
`notification_deliveries` becomes very large, the standard mitigation is time-range table
partitioning (e.g. by `created_at`, monthly), which is a Postgres/DBA-level change that needs no
application code change given the access patterns above are already scoped by `created_at`
ranges and indexed accordingly. Not needed on day one; noted here so it isn't rediscovered as a
surprise later.

## Inbound contract: `notification.requested`

Already defined in `platform-common-events` (`NotificationRequested`) — this section is how this
service *interprets* the existing fields, not a proposed contract change:

```java
public record NotificationRequested(
        UUID eventId, String eventType, String orgId, Instant occurredAt,
        String notificationType, String recipient, String channel,
        String dedupeKey, Map<String, Object> variables) implements PlatformEvent { ... }
```

- `channel` becomes an **override, not a requirement**. If the producer sets it, delivery is
  restricted to that one channel. If it's `null`, the template registry's `defaultChannels` for
  that `notificationType` decide — most notification types will declare both `EMAIL` and
  `IN_APP` there rather than every producer having to know the full channel list. This is additive
  interpretation of an existing nullable field, not a contract break.
- `recipient` is required for a `SINGLE`-audience notification and must already be the right shape
  for whichever channel(s) actually get used — the producer picking an email address for a
  notification type that also fans out to `IN_APP` is a template-authoring bug the registry should
  catch at startup (see below), not a runtime failure to design around.
- `variables` stays the loose `Map<String, Object>` per ADR-0006; unknown keys are ignored by the
  renderer the same way unknown JSON fields are ignored by the deserializer.

**Proposed additive field: `audience`.** Broadcast needs one more field on the event, not yet in
`platform-common-events`:

```java
public record NotificationRequested(
        UUID eventId, String eventType, String orgId, Instant occurredAt,
        String notificationType, String recipient, String channel,
        String dedupeKey, Map<String, Object> variables,
        String audience   // NEW — "SINGLE" (default when absent/null) | "ORG"
) implements PlatformEvent { ... }
```

- Absent or `null` → `SINGLE`, meaning every producer that exists today keeps working with zero
  changes — this is why it's additive under ADR-0006, not a breaking contract change.
- `audience = "ORG"` means "every current member of `orgId`," resolved at delivery time (not at
  publish time) against the local membership projection described next — `recipient` is ignored
  when `audience` is `ORG`.
- This field needs an actual code change to `NotificationRequested` in `platform-common-events`
  before broadcast can be produced; it's specified here so the contract and the consumer-side
  design land together rather than the consumer guessing at a shape that doesn't exist yet.

## Channel abstraction and the template registry

```java
public interface NotificationChannel {
    Channel type();
    void deliver(RenderedNotification notification, String recipient) throws ChannelDeliveryException;
}
```

`EmailChannel` and `InAppChannel` are the two Spring beans registered at startup; `ChannelRouter`
looks them up by `Channel` enum, so adding Slack later is "implement the interface, add the
`@Component`" with zero changes to the consumer or the router.

```java
public record NotificationTemplate(
        String notificationType,
        Set<Channel> defaultChannels,
        String subjectTemplate,   // plain-variable substitution, used by EMAIL
        String bodyTemplate) {}    // Thymeleaf HTML template name, used by both EMAIL and IN_APP
```

`NotificationTemplateRegistry` loads these from template files keyed by `notificationType`
(classpath resources, one file per type — same "one new file, not a new consumer" property
`PROJECT.md` already commits to) and fails service startup if a producer-declared
`notificationType` from the event catalog has no matching template. Failing loud at boot beats
failing quiet on a redelivered message a producer already sent.

**Body templates are HTML, rendered with Thymeleaf.** A notification type is two files: a small
metadata file (`notificationType`, `defaultChannels`, `subjectTemplate`) and a paired Thymeleaf
`.html` template for the body, escaping every variable it interpolates by default (`th:text`, not
`th:utext`) — the template author has to opt into raw, unescaped HTML rather than opt out of it,
which closes off the HTML-injection risk a producer-supplied variable would otherwise carry
straight into a rendered email. `subjectTemplate` stays plain-variable substitution (`{{var}}`) —
a one-line email subject has no structure worth a templating engine, and a subject line rendered
as HTML would show literal markup to the recipient. The rendered body is stored once on
`Notification.renderedBody` and reused by both channels, per the "render once, fan out many"
rule above: `EmailChannel` sends it as the email's HTML body, and `InAppChannel`'s persisted copy
is the same HTML string — the tenant dashboard's job to display or sanitize further, not this
service's.

## Broadcast notifications and future org-team-service integration

`org-team-service` (Phase 1d/2) will own organizations, teams, and membership (`PROJECT.md`).
This service doesn't wait for that to exist to get broadcast right — the audience concept, the
fan-out logic, and a local membership seam are built now, so the day `org-team-service` starts
publishing membership events, broadcast starts actually reaching people with **no change to this
service's code**.

### Why a local read-model, not a synchronous lookup

The tempting shortcut — call `org-team-service` synchronously to list an org's members when a
broadcast arrives — breaks the platform's one hard rule: *no service makes a blocking call to
another* (`PROJECT.md`, "Architecture overview"). It would also make every broadcast's latency and
availability depend on a second service being up. Instead, `notification-service` keeps its own
small, eventually-consistent projection of "who's in this org," fed by consuming membership
events, and resolves broadcasts against that local copy — the same CQRS-style read-model pattern
`audit-log-service` and `log-service` already apply to their own inputs.

```mermaid
flowchart LR
    subgraph OTS["org-team-service (future, Phase 1d/2)"]
        M[Membership changes]
    end
    subgraph Backbone["Event backbone"]
        T3[["org.member.added"]]
        T4[["org.member.removed"]]
    end
    subgraph NS["notification-service"]
        L2[OrgMembershipEventListener]
        P[(org_members<br/>local projection)]
        AR[AudienceResolver]
    end

    M -- publish --> T3
    M -- publish --> T4
    T3 --> L2
    T4 --> L2
    L2 -- upsert / mark removed --> P
    AR -- read --> P
```

### The seam, concretely

```java
public enum Audience { SINGLE, ORG }

public record Recipient(String userId, String email) {}

public interface AudienceResolver {
    List<Recipient> resolve(String orgId, Audience audience, String singleRecipient);
}
```

- `SingleAudienceResolver` (default, active today): `ORG` isn't reachable yet because nothing
  publishes `audience = ORG` until the event contract change above ships, but the interface
  already has both cases so wiring a producer doesn't require touching this service again.
- `LocalProjectionAudienceResolver`: `SINGLE` returns the one recipient as-is;
  `ORG` queries `org_members WHERE org_id = ? AND status = 'ACTIVE'` and returns one `Recipient`
  per row. Swapping this in for the default is a Spring `@ConditionalOnMissingBean` override —
  the same extension pattern every `platform-common` module already uses.
- `org_members(org_id, user_id, email, status, updated_at)` is populated by
  `OrgMembershipEventListener`, a `@KafkaListener` on two topics this document expects
  `platform-common-events`/`org-team-service` to add later: `org.member.added` and
  `org.member.removed` (payload: `orgId`, `userId`, `email`). Until `org-team-service` exists and
  publishes them, the listener has nothing to consume and the table stays empty — an `ORG`
  broadcast today resolves to zero recipients rather than failing, which is the correct, honest
  behavior for "the feature is wired but the upstream data source doesn't exist yet" (see
  [Failure modes](#failure-modes)).

### Fan-out with an audience

The processing pipeline's fan-out step changes from "one recipient × selected channels" to
"resolved recipients × selected channels":

```mermaid
sequenceDiagram
    participant L as NotificationRequestedListener
    participant AR as AudienceResolver
    participant Repo as NotificationRepository
    participant CR as ChannelRouter
    participant DDB as Postgres

    L->>AR: resolve(orgId, audience, recipient)
    AR-->>L: [Recipient, Recipient, ...]  (1 for SINGLE, N for ORG)
    L->>Repo: insert Notification (audience, rendered content) — once, regardless of N
    loop each resolved recipient
        loop each selected channel
            L->>CR: fanOut(notification, channel, recipient)
            CR->>DDB: INSERT delivery(notification_id, channel, recipient) ON CONFLICT DO NOTHING
        end
    end
```

One `Notification` row regardless of audience size — rendering happens once; only the delivery
fan-out multiplies. A broadcast to a 200-person org is 200 delivery rows sharing one rendered
notification, not 200 copies of the rendered text. Because the fan-out is idempotent per
`(notification_id, channel, recipient)`, a crash mid-broadcast resumes cleanly on redelivery: rows
already inserted are skipped, not duplicated.

## Rate limiting per organization

A single noisy or misbehaving producer (a bug that publishes the same event type in a loop, or a
legitimately large broadcast) must not degrade delivery for other tenants sharing the same Kafka
partition, and must not let one org flood its own in-app inbox or exhaust the shared SMTP relay's
quota.

**The constraint that shapes the design**: `notification.requested` is keyed by `orgId`, but a
partition holds *many* orgs' keys (partition count is far smaller than the tenant count). If
rate-limiting blocked the consumer thread when an org is over quota, it would stall every other
org's messages waiting behind it on that partition — a classic head-of-line-blocking bug hiding
inside what looks like a reasonable safety feature. So the limiter must **never block the poll
loop**; it can only make a fast accept/reject decision per delivery attempt.

```mermaid
flowchart LR
    L[NotificationRequestedListener] --> CR[ChannelRouter]
    CR --> RL{OrgRateLimiter<br/>non-blocking permission check}
    RL -- permitted --> Send[EmailChannel / InAppChannel]
    RL -- rejected --> Th[(mark delivery THROTTLED)]
    Th -. picked up later .-> Sched["DeliveryRetryScheduler<br/>@Scheduled sweep"]
    Sched --> RL
```

- `OrgRateLimiter` is a Resilience4j `RateLimiter` registry keyed by `orgId` (one limiter instance
  per org, created on first use, config default e.g. N deliveries/minute via
  `pallet.notification.rate-limit.*` — no hardcoded threshold, per the shared modules' own rule
  against hardcoding tunables). It's consulted with a **zero-wait permission check**
  (`RateLimiter.acquirePermission()`, not `executeRunnable` with a wait timeout) — reject
  immediately, never wait.
- On rejection, the delivery row is written as `THROTTLED` rather than attempted — the event has
  already been fully consumed and acknowledged; this is a delivery-pacing decision, not a
  consumption failure, so it must never trigger Kafka redelivery or a DLT entry.
- `DeliveryRetryScheduler`, a `@Scheduled` task independent of the Kafka consumer, periodically
  sweeps `THROTTLED` rows (the partial index from [Data model](#data-model)) and re-attempts them
  through the same `ChannelRouter` → `OrgRateLimiter` path. This decouples *ingestion speed*
  (bounded only by Kafka consumer throughput) from *delivery pacing* (bounded by each org's
  quota) — the two were conflated in a naive "rate-limit the listener" design and shouldn't be.
- The same `OrgRateLimiter` registry can gate the read API too (a per-org or per-user request
  cap on `GET /api/v1/notifications`) using Resilience4j's standard Spring MVC integration —
  worth adding once real traffic shows it's needed; the registry already exists either way.

## Processing pipeline

The diagram below is the default, `SINGLE`-audience path — one recipient, no throttling. For an
`ORG` broadcast, the fan-out step expands per
[Broadcast notifications](#broadcast-notifications-and-future-org-team-service-integration); for a
throttled delivery, the send step ends in `THROTTLED` instead of `SENT`/`FAILED` per
[Rate limiting](#rate-limiting-per-organization). Both are drawn separately above rather than
folded into one crowded diagram.

```mermaid
sequenceDiagram
    participant K as Kafka (notification.requested)
    participant L as NotificationRequestedListener
    participant G as EventIdempotencyGuard
    participant Reg as TemplateRegistry
    participant Repo as NotificationRepository
    participant CR as ChannelRouter
    participant EC as EmailChannel
    participant IC as InAppChannel
    participant RES as ExternalCall (Resilience4j)
    participant SMTP as SMTP relay
    participant DDB as Postgres

    K->>L: NotificationRequested
    L->>G: reserve(eventId)
    alt duplicate delivery
        G-->>L: already reserved (unique constraint hit)
        L-->>K: ack, no-op
    else first delivery
        G-->>L: reserved
        L->>Reg: resolve(notificationType)
        Reg-->>L: template + defaultChannels
        L->>Repo: insert Notification (rendered title/body)
        L->>CR: fanOut(notification, resolvedChannels, recipient)
        par EMAIL (if selected)
            CR->>EC: deliver(notification, recipient)
            EC->>RES: execute(sendMail)
            RES->>SMTP: SMTP send
            SMTP-->>RES: ack / timeout / error
            RES-->>EC: result or ExternalServiceException
            EC->>DDB: upsert delivery(EMAIL, SENT|FAILED, lastError?)
        and IN_APP (if selected)
            CR->>IC: deliver(notification, recipient)
            IC->>DDB: insert delivery(IN_APP, SENT, sentAt=now)
        end
        L-->>K: ack
    end
```

If the listener throws (a channel that must not swallow its own failure — see
[Failure modes](#failure-modes) for which ones do), `platform-common-messaging`'s consumer factory
takes over: exponential-backoff redelivery, then `DeadLetterPublishingRecoverer` to
`notification.requested.DLT`. Nothing in this service hand-rolls retry or DLT logic — that
plumbing is shared, per `docs/workflows/platform-common/05-messaging.md`.

## Read API: in-app notifications

```
GET   /api/v1/notifications?status=UNREAD&page=&size=&sort=
GET   /api/v1/notifications/{deliveryId}
PATCH /api/v1/notifications/{deliveryId}/read
POST  /api/v1/notifications/read-all
GET   /api/v1/notifications/unread-count
```

- Scoped to `IN_APP` deliveries only — email has nothing to list.
- Authenticated via `platform-common-security`; `recipient` on the query is the caller's own `sub`
  claim, never a path/query parameter a client sets — a user must not be able to page through
  another user's notifications by changing an id. `org_id` on the token additionally scopes the
  underlying `Notification` row, so cross-tenant leakage needs two independent claim mismatches,
  not one.
- Responses use `PageResponse<NotificationDto>` wrapped in `ApiResponse<T>`
  (`platform-common-api`) like every other Pallet endpoint; list input is a `PageQuery`.
- `PATCH .../read` is idempotent — marking an already-read notification read again is a 200, not
  a 409; `read_at` only ever moves from `null` to a timestamp, never back.

```mermaid
sequenceDiagram
    participant FE as Tenant dashboard
    participant GW as api-gateway
    participant Ctrl as NotificationController
    participant Svc as NotificationQueryService
    participant DDB as Postgres

    FE->>GW: GET /notifications?status=UNREAD (JWT)
    GW->>Ctrl: forward + validated token
    Ctrl->>Svc: list(orgId, userId, pageQuery)
    Svc->>DDB: query notification_deliveries JOIN notifications
    DDB-->>Svc: page of rows
    Svc-->>Ctrl: PageResponse<NotificationDto>
    Ctrl-->>FE: ApiResponse<PageResponse<NotificationDto>>
```

## Distributed systems mechanisms

| Concern | Mechanism | Where |
|---|---|---|
| At-least-once → effectively-once | Unique constraint on `notifications.source_event_id`; insert failure = "already handled" | `EventIdempotencyGuard` impl backed by the `notifications` table (overrides the default Redis guard, same pattern `05-messaging.md` describes for the delivery log) |
| Logical dedupe (two producers, one fact) | Partial unique index on `(org_id, dedupe_key)` | `notifications` table |
| Per-channel fan-out idempotency | Unique `(notification_id, channel)`, `INSERT ... ON CONFLICT DO NOTHING` | `notification_deliveries` |
| Ordering | Kafka partition key = `orgId` (already set by `PlatformEventPublisher`) — one tenant's notifications stay ordered; no cross-tenant ordering guarantee needed | Kafka + `platform-common-messaging` |
| Backpressure / horizontal scale | Stateless consumer, scales by adding instances up to the partition count of `notification.requested` | Consumer group, `platform-common-messaging` |
| Fast, bounded retry per external call | Retry + circuit breaker + time limiter around each SMTP send | `ExternalCall` (`platform-common-resilience`) wrapping `EmailChannel` |
| Slow-consumer isolation | A tripped email circuit breaker fails fast instead of blocking the poll loop; in-app deliveries for the same batch are unaffected since they don't share the breaker | Per-channel `ExternalCall` instances, not one shared breaker |
| Per-tenant fairness / noisy-neighbor protection | Non-blocking, per-org rate limiter on the send path; rejections become `THROTTLED` deliveries drained by a separate scheduled sweep, never a blocked partition | `OrgRateLimiter` (Resilience4j `RateLimiter` registry) + `DeliveryRetryScheduler` — see [Rate limiting](#rate-limiting-per-organization) |
| Eventually-consistent local read-model for cross-service data | Membership needed for broadcast is consumed from events into a local table instead of queried synchronously | `org_members` projection + `OrgMembershipEventListener` — see [Broadcast notifications](#broadcast-notifications-and-future-org-team-service-integration) |
| Redelivery + poison-message handling | Exponential backoff redelivery, then `DeadLetterPublishingRecoverer` → `notification.requested.DLT` | `platform-common-messaging` |
| Tracing | One trace spans consume → render → fan-out → send, and separately, one span per inbound read-API call | `platform-common-observability` (`@Monitored`, correlation interceptor) |
| Metrics | Counters: `notifications.sent`, `.failed`, `.retried`, `.dead_lettered`, `.read`; histogram: send latency by channel; gauge: breaker state | Micrometer via `platform-common-observability` + `-resilience` |
| Multi-tenancy isolation | `org_id` on every row; read API additionally scopes by `sub` (recipient) | Postgres schema `notification`; `platform-common-security` |
| Consistency model | Eventual: a producer's event and a delivered notification are never in the same transaction (no dual-write across services) — the producer doesn't block on delivery, and delivery lag is bounded by consumer lag, not by this service's own logic | Inherent to the event-driven design; see `PROJECT.md`'s reliable-publishing open question for the producer side of this |

## Delivery state machine

Two independent retry rings apply before a delivery reaches a terminal state: Resilience4j's
retry (fast, in-process, a handful of attempts within one consumer invocation) and Kafka's
consumer-level redelivery (slower, whole-message, backs off across multiple poll cycles). A
delivery only reaches `DEAD_LETTERED` after both are exhausted.

```mermaid
stateDiagram-v2
    [*] --> PENDING: delivery row created
    PENDING --> SENT: channel send succeeds
    PENDING --> THROTTLED: org rate limiter rejects (non-blocking check)
    THROTTLED --> PENDING: DeliveryRetryScheduler re-attempts on its next sweep
    PENDING --> FAILED: Resilience4j retry budget exhausted OR breaker open OR non-retryable error
    FAILED --> PENDING: whole event redelivered by Kafka (new consumer attempt)
    FAILED --> DEAD_LETTERED: Kafka-level retries also exhausted → event lands on .DLT
    SENT --> READ: IN_APP only — recipient opens it
    SENT --> [*]
    READ --> [*]
    DEAD_LETTERED --> [*]: recorded as failed, visible in metrics/audit, not silently lost
```

`THROTTLED` is never a Kafka-retry trigger — it's resolved entirely by
`DeliveryRetryScheduler`, independent of consumer redelivery. It only ever transitions back to
`PENDING`, never straight to `DEAD_LETTERED` — a throttled delivery is retried until it sends or
until it fails for a real (non-quota) reason, not abandoned for being rate-limited.

## Failure modes

| Scenario | Behavior |
|---|---|
| SMTP host down / slow | Circuit breaker opens after the configured failure threshold; further `EMAIL` deliveries fail fast (`FAILED`) instead of blocking the consumer thread; breaker state is a Prometheus gauge so it's visible before it becomes an incident |
| Malformed / undeserializable event | `ErrorHandlingDeserializer` routes straight to `notification.requested.DLT` — never reaches the listener |
| Unknown `notificationType` (no template) | Startup-time validation should have caught this for any type in the event catalog; if it still happens (a producer bug shipped ahead of its template), the listener throws a non-retryable exception — Kafka redelivery would just repeat the same failure, so this should skip straight to DLT rather than burn the full backoff schedule (configure as a non-retryable exception type in the consumer factory, per `06-resilience.md`'s exception classification) |
| One channel fails, another in the same fan-out succeeds | Independent delivery rows — `IN_APP` succeeding while `EMAIL` fails (or vice versa) is a normal, expected, partially-successful outcome, not a transaction to roll back |
| Redelivered event after a successful first pass | `EventIdempotencyGuard` short-circuits before any channel runs — zero duplicate emails, zero duplicate in-app rows |
| Reader marks an already-read notification as read again | No-op, 200 — `read_at` is monotonic |
| Recipient claim on the token doesn't match the delivery's `recipient` | 404, not 403 — don't confirm to a caller that a notification exists for someone else |
| `ORG` broadcast arrives before `org-team-service` exists or before it has published that org's members | `AudienceResolver` returns zero recipients; the `Notification` row is still created (content isn't lost) but produces no deliveries — visible as a `notifications.broadcast_empty` counter, not a silent drop, so it's diagnosable rather than mysterious |
| An org sustains volume above its rate limit for a long time | Deliveries accumulate as `THROTTLED` and keep getting retried by the sweep; this is a capacity/backlog signal (alert on `THROTTLED` count and oldest-`THROTTLED`-age, not just on failures) rather than a state this design silently resolves — see [Open questions](#open-questions--deferred) |
| Broadcast crashes mid-fan-out (pod killed after 80 of 200 deliveries inserted) | Safe to redeliver or restart: the `(notification_id, channel, recipient)` unique constraint makes re-running the fan-out for the same notification a no-op for the 80 already inserted and completes the remaining 120 |

## Component view

```mermaid
flowchart TB
    subgraph NotifSvc["notification-service"]
        direction TB
        L["NotificationRequestedListener<br/>(@KafkaListener)"]
        G["EventIdempotencyGuard impl<br/>(notifications.source_event_id)"]
        Reg[NotificationTemplateRegistry]
        Rend[TemplateRenderer]
        AR[AudienceResolver]
        MRepo[OrgMembershipRepository]
        ML["OrgMembershipEventListener<br/>(@KafkaListener, future topics)"]
        Router[ChannelRouter]
        RL[OrgRateLimiter]
        Sched["DeliveryRetryScheduler<br/>(@Scheduled)"]
        EC2[EmailChannel]
        IC[InAppChannel]
        Repo1[NotificationRepository]
        Repo2[NotificationDeliveryRepository]
        Ctrl[NotificationController]
        QSvc[NotificationQueryService]
    end

    L --> G
    L --> Reg --> Rend
    L --> AR --> MRepo
    ML --> MRepo
    L --> Repo1
    L --> Router
    Router --> RL
    RL --> EC2 --> Repo2
    RL --> IC --> Repo2
    Sched --> RL
    Sched --> Repo2
    Ctrl --> QSvc --> Repo1
    QSvc --> Repo2
```

## Deployment and scaling view

```mermaid
flowchart LR
    subgraph Kafka["notification.requested (partitioned by orgId)"]
        Pa[partition 0]
        Pb[partition 1]
        Pc[partition 2]
    end

    subgraph CG["Consumer group: notification-service"]
        I1[instance 1]
        I2[instance 2]
        I3[instance 3]
    end

    GW[api-gateway]

    subgraph HTTP["Same instances also serve the read API"]
        I1
        I2
        I3
    end

    PG[(Postgres — notification schema)]

    Pa --> I1
    Pb --> I2
    Pc --> I3
    GW --> I1
    GW --> I2
    GW --> I3
    I1 --> PG
    I2 --> PG
    I3 --> PG
```

The consumer and the read API live in one deployable in v1 — no reason yet to split them, and
splitting later (a dedicated read-replica-backed query service) is cheap precisely because
`NotificationQueryService` only talks to the repositories, never to the Kafka listener. Scale by
adding instances up to the partition count of `notification.requested`; beyond that, add
partitions (a one-time operational step, not a code change, since the key is already `orgId`).

## Package layout and dependencies

```
services/notification-service/
└── src/main/java/io/pallet/notification/
    ├── NotificationServiceApplication.java
    ├── consumer/        NotificationRequestedListener
    ├── idempotency/      NotificationEventIdempotencyGuard
    ├── template/         NotificationTemplate, NotificationTemplateRegistry, TemplateRenderer
    ├── audience/          Audience, Recipient, AudienceResolver, SingleAudienceResolver,
    │                     LocalProjectionAudienceResolver, OrgMember (entity),
    │                     OrgMembershipRepository, OrgMembershipEventListener
    ├── channel/          NotificationChannel, EmailChannel, InAppChannel, ChannelRouter
    ├── ratelimit/         OrgRateLimiter, DeliveryRetryScheduler
    ├── domain/           Notification, NotificationDelivery, Channel, DeliveryStatus (JPA entities)
    ├── repository/       NotificationRepository, NotificationDeliveryRepository
    ├── api/               NotificationController, NotificationQueryService, NotificationDto
    └── config/           NotificationServiceProperties
```

POM adds, beyond the reactor parent: `platform-common-api`, `-exception`, `-events`,
`-messaging`, `-observability`, `-resilience`, `-security` (read API auth);
`spring-boot-starter-data-jpa` + `postgresql` + `flyway-core` for persistence;
`spring-boot-starter-mail` for the `EmailChannel`'s `JavaMailSender`;
`spring-boot-starter-webmvc` + `-validation` for the read API. Config:
`config-repo/notification-service.yml` (SMTP block from Phase 1b, Postgres URL, template
directory) alongside the shared `config-repo/application.yml`.

Testing follows `CONTRIBUTING.md`: `*IntegrationTest` classes use Testcontainers for Kafka and
Postgres. There's no `testcontainers-` module for an SMTP relay — use GreenMail (in-process fake
SMTP) to assert rendered email content in `*IntegrationTest`s, and reserve real Mailpit for the
manual, docker-compose-driven verification step ("publish an event, see the email land in the
Mailpit UI at `:8025`").

## Extension points

Adding **Slack** later: implement `NotificationChannel` with `type() == SLACK`, wrap its HTTP
call in `ExternalCall`, register the bean, add `SLACK` to whichever templates' `defaultChannels`
should include it. No change to the listener, the idempotency guard, the DTOs, or the read API.

Adding **tenant webhooks**: same shape, plus a per-org webhook URL lookup this service doesn't
own yet (likely `org-team-service`, once it exists) — the channel implementation is where that
lookup would go, not a fork in the consumer.

Adding **notification preferences** (a user muting a type or channel): a lookup the
`ChannelRouter` consults before fan-out, sitting between "template says these channels" and
"actually deliver" — additive, doesn't change the delivery/read data model.

## Open questions / deferred

- **Real-time push for in-app.** Decided direction: not now. The read API is pull/poll-based in
  this foundation — a live unread-count badge polls `GET .../unread-count` on an interval. A
  WebSocket/SSE push comes later, following the same pattern `PROJECT.md`'s log-service tail
  already establishes (a JWT-checked live stream scoped to the caller). Nothing in the data model
  blocks adding it: a push layer would sit beside `NotificationQueryService`, publishing the same
  `NotificationDto` rows it already produces, triggered off the same writes `InAppChannel` and
  `DeliveryRetryScheduler` already make — no reshaping of `notifications` /
  `notification_deliveries` needed when that phase starts.
- **Sustained per-org throttling.** The rate-limiting design (above) handles a burst cleanly, but
  doesn't yet define a cap on how long a delivery may sit `THROTTLED`, or what happens if an org's
  sustained volume permanently exceeds its quota (unbounded backlog vs. an eventual drop with an
  audit trail vs. an alert that pages a human to raise the org's limit). Needs a decision once
  real traffic patterns exist to size the default limits against — premature to fix a number now.
- **Broadcast audience beyond "every active member."** `ORG` today means every row in
  `org_members` with `status = 'ACTIVE'`. Whether a broadcast should ever be scoped further (only
  a given role, e.g. owners/admins) is an `org-team-service`-shaped question — the
  `AudienceResolver` interface can grow a third case then; not needed for the seam to be useful
  now.

## Relationship to existing planning docs

`docs/workflows/ROADMAP.md` Phase 1c and `PROJECT.md`'s `notification-service` paragraph describe
email-only delivery with a `delivery_log` table that "doubles as the idempotency record." This
document folds that idea into the two-table model above (`notifications` +
`notification_deliveries`): the idempotency check still lives on a unique-constraint insert, just
against `notifications.source_event_id` instead of a table named `delivery_log`, and the same
table now also carries the fan-out and read-state that in-app needs. The event contract
(`NotificationRequested`) doesn't change — `channel` is reinterpreted as an optional override
rather than a required field, which was already legal (it's a plain nullable `String`).

Before Sprint 1 of `docs/workflows/notification-service/` starts, this expansion in scope (a
second channel, two new REST endpoint groups, `platform-common-security` as a new dependency for
this service, an `audience` field on `NotificationRequested`, and a local `org_members`
projection fed by topics `org-team-service` doesn't publish yet) should get a short ADR, per
`CONTRIBUTING.md`'s rule that an architecture-changing decision ships an ADR in the same PR — this
file is the design; the ADR is the one-page record of *why* in-app and broadcast joined the
foundation instead of arriving as later phases. Two follow-ups this design creates for other
docs, worth listing in that ADR rather than losing track of:

- `platform-common-events`: add `audience` to `NotificationRequested`, and reserve
  `org.member.added` / `org.member.removed` in `Topics` once `org-team-service`'s own workflow
  defines their exact payload — this document assumes their shape, it doesn't own it.
- `docs/workflows/ROADMAP.md` Phase 1c's checkpoint table should grow a line for the
  audience/rate-limit/membership-projection work when `docs/workflows/notification-service/` is
  written sprint-by-sprint, so the roadmap doesn't silently fall behind this design.
