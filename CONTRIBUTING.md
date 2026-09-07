# Contributing to Pallet

Pallet is a build-in-public learning project. The premise is that other people
follow along and, once a service gets interesting, send pull requests. This
document is how the build fits together.

## The reactor build

One Maven multi-module reactor, one parent POM (`pom.xml`) that pins Spring
Boot, Spring Cloud, Resilience4j, and every plugin version. Two aggregator
modules:

- `platform-common/` — libraries every service depends on. Three modules:
  `platform-common-events` (Kafka contracts), `platform-common-security`
  (resource-server baseline + `org_id` check), `platform-common-observability`
  (metrics/tracing/logging dependencies).
- `services/` — one module per microservice, added as each is built.

Every service depends on `platform-common-observability` (which transitively
brings Actuator, the Prometheus registry, and OTLP export) and, once it serves
tenant data, on `platform-common-security`.

```bash
./mvnw -pl services/config-server -am package   # build one service + its deps
./mvnw clean verify                             # whole reactor, unit + integration tests
./mvnw -P security verify                       # + SpotBugs and OWASP Dependency-Check
```

`make` wraps the common commands — `make help`.

## Spring Boot 4 gotchas

This project is on Spring Boot 4.1 / Java 21. If you copy a snippet written for
Boot 3, expect these to bite ([ADR-0005](docs/adr/0005-java-21-spring-boot-4.md)):

- Starters are modular: `spring-boot-starter-webmvc` (not `-web`),
  `spring-boot-starter-security-oauth2-resource-server` (not
  `-oauth2-resource-server`), `spring-boot-starter-aspectj` (not `-aop`).
- Test slices are separate starters (`spring-boot-starter-webmvc-test`, …), each
  pulling in `spring-boot-starter-test`.
- Jackson 3: `ObjectMapper`/databind is `tools.jackson.*`; annotations stay under
  `com.fasterxml.jackson.annotation`.
- Testcontainers 2.x: artifact ids are prefixed — `testcontainers-postgresql`,
  `testcontainers-kafka`, `testcontainers-junit-jupiter`.

## Conventions

- Base package `io.pallet`, then the service name (`io.pallet.configserver`).
- Tests: unit tests are `*Test`, integration tests are `*IntegrationTest`.
  Surefire runs the first, Failsafe (`verify`) runs the second. Integration
  tests use Testcontainers for Kafka, Postgres, ClickHouse, and Keycloak — never
  mock those; the failure modes they hide are the point of the project.
- Events: add a flat record to `platform-common-events` implementing
  `PlatformEvent`, a `Topics` constant, past-tense `domain.fact` name.
- Config: shared config is served by `config-server` from `config-repo/`.
  Service-specific config is `config-repo/<service-name>.yml`. Never commit a
  real secret — see [`SECURITY.md`](SECURITY.md).
- Code style: no comments that restate the code; keep them for a genuine hidden
  constraint. Prefer editing an existing class over a new abstraction for a
  one-off.

## CI

`.github/workflows/build.yml` builds and tests the whole reactor on every PR to
`main`, with a second job for the security scans. The reactor is small enough
today that a full build per change is fine; once there are many services this
splits into a paths-filter + matrix so a change to one service (or a
`platform-common` module it depends on) only rebuilds what it affects.

## Pull requests

- Branch off `main`. Keep a PR to one service or one shared change.
- `./mvnw clean verify` must pass. Don't disable a test or use `--no-verify` to
  get green — fix the cause.
- A decision that changes architecture gets an ADR in the same PR.
