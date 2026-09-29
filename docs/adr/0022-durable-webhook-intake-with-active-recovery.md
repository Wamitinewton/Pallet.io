# 22. Durable webhook intake with active recovery

- Status: accepted
- Date: 2026-09-28

## Context

`git-integration-service` is the first Pallet service whose inbound traffic comes from a third party
rather than a tenant: GitHub POSTs a webhook for every push, installation change, and repository
change, and a push is the first hop of the deploy path. Three properties of that sender shape the
design (`docs/git-integration-service/ARCHITECTURE.md` §Webhook ingestion, §The chain rule,
§Recovering lost deliveries):

- GitHub waits 10 seconds for an answer, and records anything slower, or any `5xx`, as a failed
  delivery. It **never redelivers on its own**; a failed delivery stays failed until someone asks
  for it again through the API, within three days.
- It delivers at least once and in no guaranteed order. The same `X-GitHub-Delivery` can arrive
  twice, and two pushes to one branch can arrive reversed.
- A push payload can be many megabytes (GitHub caps it at 25 MB), far over Kafka's default
  one-megabyte message limit.

A lost push is invisible: nothing fails, the commit just never builds. `billing-service` will face the
same shape with Paystack's webhooks, so the pattern is recorded here rather than only in one
service's design.

## Decision

Three parts, which only make sense together.

1. **Persist, then acknowledge, in Postgres.** The handler reads the body once with a hard size
   cap, verifies the HMAC signature over the raw bytes in constant time (current and previous
   secret, so rotation drops nothing), reads the few envelope fields it needs under explicit parser
   limits, and inserts one row keyed by the delivery id with `ON CONFLICT DO NOTHING`. One row
   inserted is `202`, none is `200`. Nothing is parsed or stored before the signature passes, and
   nothing is published or called from the request. Processing is a separate poller that claims rows
   with `FOR UPDATE SKIP LOCKED` and writes its effects, its events (through the outbox) and the
   row's `PROCESSED` status in one transaction.

   Postgres, not Kafka, holds the delivery: payloads exceed the message limit and raising it
   platform-wide for one producer is a poor trade; processing needs Postgres anyway, so a Kafka
   buffer would add a second hard dependency to the ingest path rather than replace one; and both
   dedupe and recovery need a lookup by delivery id, which a topic can't answer.
2. **The chain rule** handles order and duplicates in processing: a push advances an app's branch
   head only if its `before` is the head Pallet last accepted, under a row lock, with a compare call
   to GitHub as the fallback. A duplicate or a stale push changes nothing.
3. **Active recovery**, because GitHub won't retry: a redelivery sweeper reads GitHub's delivery log
   and asks again for every failed delivery Pallet doesn't hold, and a head reconciler compares each
   linked branch's head on GitHub with the last accepted one, which covers anything older than
   GitHub's three-day window or lost before GitHub logged it.

## Consequences

- Readiness depends on Postgres, not on Kafka. The webhook keeps accepting while Kafka is down;
  the delivery table and the outbox exist so that it can.
- A Postgres outage answers `503` (GitHub records a failed delivery) rather than a `500` or a hang:
  the handler takes a connection with a 3-second timeout, and `platform-common-exception` maps an
  unreachable database (`DataAccessResourceFailureException`, `CannotCreateTransactionException`
  and their transient kin) to `503 SERVICE_UNAVAILABLE` for every service. The sweeper recovers
  those deliveries within GitHub's three days; the reconciler covers beyond that.
- The request path is one HMAC per configured secret, one bounded parse, one insert, so its
  latency is the insert's. The acknowledgement SLO (p99 under 200 ms) is a property of Postgres,
  not of anything downstream.
- The delivery table grows with traffic. Payloads are nulled after a retention period and rows
  deleted after a longer one, and the processor, sweeper and reconciler all need their own
  alerting (backlog age, parked rows, reconciler finds).
- A second webhook source (Paystack for `billing-service`) reuses the same shape: its own
  deliveries table keyed by the provider's event id, its own signature scheme, the same
  acknowledge-then-process split, and its own recovery path, since each provider's redelivery
  behavior differs.
