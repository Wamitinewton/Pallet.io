# 9. Temporal for saga orchestration and durable workflow execution

- Status: accepted
- Date: 2026-09-10

## Context

`docs/PROJECT.md`'s Core concepts table names the saga pattern as "a hand rolled state machine
over Kafka commands and events," owned by `deploy-orchestrator-service` (the deployment saga:
queued → building → pushing → provisioning → routing → health checking → live/failed/rolled back)
and `billing-service` (a payment and provisioning saga against Paystack). Neither service has been
built yet — `deploy-orchestrator-service` starts in Build roadmap phase 5, `billing-service` in
phase 7 — so this decision lands before any hand-rolled state machine exists to migrate away from,
at essentially zero implementation cost beyond documentation today.

A hand-rolled saga over Kafka needs its own persistence for in-flight state, its own timer/timeout
handling for a step that never completes, its own correlation logic to match an inbound event back
to the saga instance waiting on it, and hand-written compensation ordering. That's exactly the kind
of infrastructure `platform-common-messaging` and `platform-common-resilience` already generalize
for simpler request/response and pub/sub cases — but saga orchestration is a distinct problem
(long-running, stateful, needs exactly-once step execution and ordered compensation on failure),
and hand-rolling it a second time inside `deploy-orchestrator-service` would duplicate that effort
per-saga rather than solving it once, the same argument that justified building
`platform-common-messaging`/`-resilience` before the first feature service.

Temporal is a production-grade, open-source durable execution engine built for exactly this: a
workflow function runs to completion across crashes, deploys, and days-long waits, with automatic
retries, durable timers, and built-in support for saga-style compensation, without needing a
database table for "which step is this saga on."

## Decision

Adopt Temporal as the orchestration engine for both named sagas, and as the default choice for any
future service that needs multi-step, long-running orchestration with compensation.

**Scope.** `deploy-orchestrator-service` and `billing-service` each embed a Temporal Worker (Java
SDK — `temporal-sdk` + `temporal-spring-boot-starter`) inside their own Spring Boot process, added
as a dependency in that service's own `pom.xml`, not as a new `platform-common-*` module. Only
these two of the twenty-four services need it; a shared module for two consumers repeats the
mistake `platform-common` itself was designed to avoid — an abstraction built for a use case that
doesn't exist yet. If a third saga-shaped service shows up later, that's the trigger to reconsider.

**Where Temporal replaces the hand-rolled state machine, and where Kafka stays.** The deployment
saga's actual steps are Kubernetes API calls (create/patch a `Deployment`, `Service`, `Ingress` —
already a synchronous "third-party" call per `docs/PROJECT.md`'s architecture section, wrapped in
`platform-common-resilience`) interleaved with waiting on other services' async reactions to that
Kubernetes state (`dns.record.updated`, `tls.cert.issued`, `health.check.failed`). That shape
doesn't change:

- Activities wrap the calls that were already synchronous external edges — the Kubernetes API,
  Paystack, GitHub, ACME, a DNS provider's API — the same edges `docs/PROJECT.md` already carves
  out of the "no blocking calls between our own services" rule. Temporal's per-activity retry
  policy sits *alongside* `platform-common-resilience`'s circuit breaker inside the activity
  implementation, not instead of it: retry-on-schedule and stop-calling-a-known-dead-dependency
  are complementary, and Temporal has no circuit-breaker primitive of its own.
- Cross-service coordination — waiting for `dns-service`, `tls-service`, or `health-check-service`
  to react — stays event-driven over Kafka exactly as today. What changes is the destination:
  instead of a hand-rolled table correlating an inbound event to a saga instance, a thin Kafka
  listener turns the matching event into a Temporal signal on the workflow keyed by the same
  `deploymentId`. The "no service makes a blocking call to another" rule is unchanged; nothing
  here adds a new synchronous edge between Pallet's own services.
- If a step genuinely needs synchronous point-to-point data from another Pallet service (not an
  event another service already emits), calling it directly from inside an activity is an accepted
  exception on the same footing as a third-party call: it runs on a Temporal worker thread, not a
  request-handling thread, so a slow response delays only that one workflow execution, never
  another tenant's request. This is available, not mandated — most saga steps in this project are
  Kubernetes-API-or-third-party-call plus a Kafka wait, per the point above.

**Compensation.** Each saga uses the Java SDK's `Saga` helper: a forward activity registers its
compensating action (`saga.addCompensation(...)`) as it succeeds, and `saga.compensate()` runs
them in LIFO order the moment any step fails — the literal replacement for "the orchestrator
issues compensating commands" in `docs/PROJECT.md`'s architecture section, and for
`deploy-orchestrator-service`'s state list (`queued, building, pushing, provisioning, routing,
health checking, live, failed, rolled back`) as a hand-maintained enum — the workflow's own
execution history *is* that state machine.

**Identity and idempotency.** Workflow ID is the domain id already at hand
(`deployment-{deploymentId}` for the deploy saga, `billing-saga-{orgId}-{invoiceId}` for billing),
with `WorkflowIdReusePolicy.ALLOW_DUPLICATE_FAILED_ONLY` — a redelivered `build.succeeded` that
tries to start the same saga twice attaches to the existing run, or is rejected once it's already
succeeded, instead of needing a hand-rolled idempotency table the way `notification-service` needs
one for its own event consumption.

**Deployment topology.** One self-hosted Temporal server per environment (dev/staging/prod), not
Temporal Cloud, running on the same Kubernetes cluster as Pallet's own control-plane services (not
the tenant EKS/GKE clusters `scheduler-service` registers) — consistent with the project already
running its own Postgres, Kafka, and Keycloak rather than managed equivalents, and with ADR-0002's
split between "where the platform's own services run" and "where tenant workloads run."
Persistence and, to start, visibility both back onto Postgres — the same database the rest of the
platform already operates, rather than introducing Elasticsearch for visibility on day one. One
Temporal **Namespace** per environment, not per tenant, mirroring ADR-0003's one-realm-not-one-per-
org choice: tenant isolation is a `deploymentId`/`orgId` field on the workflow, not a namespace
boundary.

**Security.** mTLS between workers and the Temporal frontend in staging/prod, matching every other
internal edge in the platform; the local dev server (`temporal server start-dev`, plaintext,
in-memory) needs none. Anything an activity calls synchronously follows the client-credentials
pattern ADR-0003 already established.

**Versioning.** Workflow code must stay deterministic (no direct wall-clock time, randomness, or
non-deterministic branching without `Workflow.getVersion`). Every change to a workflow's shape
ships behind Temporal's Worker Build ID versioning, so an in-flight deployment saga keeps running
under the code it started with while new sagas pick up the new build. CI gains a replay-test step
(`WorkflowReplayer` against histories captured from the previous version) before a workflow-code
change merges — the direct analog of `./mvnw clean verify` catching a regression before it ships
(see `CONTRIBUTING.md`).

**Observability.** The Temporal Java SDK's Micrometer metrics bridge feeds the same
Prometheus/Grafana stack every other service already reports to; its OpenTelemetry interceptor
joins workflow and activity spans to the trace already carrying the webhook-to-live-app path
through Jaeger, so a deployment still shows up as one trace end to end exactly as
`docs/PROJECT.md`'s architecture section already promises. The Temporal Web UI becomes a local dev
endpoint (`:8233`) the same way Kafka UI and Jaeger already are. Domain events
(`deploy.state.changed`, `deploy.step.completed`, `audit.event.recorded`) are still published from
inside the workflow/activities exactly as today — Temporal's own execution history is operational
debugging data, not a replacement for `audit-log-service`'s immutable record, and it's retained on
a much shorter window (a default closed-workflow retention, e.g. 30 days) than ClickHouse's.

## Consequences

**Easier**: no hand-rolled saga-state table, timeout handling, or event-to-saga correlation logic
to write or test for either service; compensation ordering is a library call instead of
hand-maintained reverse-step logic; a crashed worker or a bad deploy resumes a saga exactly where
it left off, for free; replay-based testing catches a workflow-breaking change in CI instead of in
a stuck production saga.

**Harder / new work this creates**: a Temporal server to operate per environment (a new Helm
chart, a new Postgres database, a new thing that can be down); workflow code carries a determinism
constraint ordinary Java code doesn't, and a leaked non-deterministic change isn't always the kind
of bug a code reviewer expects to check for; workflow-code changes need a versioning discipline
(Build ID ramp-up, replay tests) that a stateless REST endpoint never needed.

**Not done here**: `docker-compose.yml` wiring for the local Temporal dev server. Per the pattern
`docs/workflows/ROADMAP.md` Phase 1b already set with Mailpit (documented as needed "from the
start," but actually wired into `docker-compose.yml` only right before `notification-service`
needed it), the Temporal dev server lands in `docker-compose.yml` when
`deploy-orchestrator-service`'s own phase starts (Build roadmap phase 5), not now — there's no
consumer yet.

## Alternatives considered

- **Keep the hand-rolled Kafka state machine.** Rejected: it's the exact kind of per-saga
  reinvention `platform-common-messaging`/`-resilience` already exist to avoid at the message
  level; a second hand-rolled durable-execution engine for sagas specifically buys nothing a
  mature one doesn't already solve, and this project's stated bar is "a reason for each pattern to
  exist," not "avoid a dependency by hand-rolling it worse."
- **Temporal Cloud instead of self-hosting.** Rejected for now: the project already runs its own
  Postgres, Kafka, and Keycloak rather than managed equivalents specifically to demonstrate
  operating this infrastructure, and self-hosting is a small addition once Kubernetes and Postgres
  are already in place (ADR-0002). Revisit if operating the Temporal server itself becomes a real
  time sink — the same trigger that would justify revisiting any other piece of self-hosted infra
  here.
- **A `platform-common-workflow` module wrapping Temporal.** Rejected: only two of twenty-four
  services need it, and every existing `platform-common` module exists because most or all
  services need what it provides (messaging, resilience, security, observability). Two consumers
  isn't enough to justify a shared abstraction over the SDK's own API; revisit if a third
  saga-shaped service appears.
- **Cadence (Temporal's predecessor/sibling engine).** Rejected: Temporal is the actively
  developed fork with the larger ecosystem, better documentation, and the SDK this project would
  actually be maintained against.
