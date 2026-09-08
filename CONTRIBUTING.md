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
- Whitespace/EOL/charset style is defined once in `.editorconfig` and enforced
  by [`editorconfig-checker`](https://github.com/editorconfig-checker/editorconfig-checker)
  via a `pre-commit` hook. Indent *style* (tabs vs. spaces) is enforced, but
  indent *size* is not — nested Markdown lists and XML/Java continuation-line
  alignment don't fit a strict "multiple of N spaces" rule, so `IndentSize` is
  disabled in `.editorconfig-checker.json`. Set up once per clone:
  ```bash
  pip install pre-commit   # or: brew install pre-commit / pipx install pre-commit
  pre-commit install
  ```
  `make format-check` runs the same check on demand (`pre-commit run
  --all-files`). It also runs in CI (`style` job) regardless of whether the
  local hook is installed.

## CI

`.github/workflows/build.yml` is path-filtered. A push to `main` always runs a
full reactor `verify` — the canonical green signal. A PR is classified by the
`scope` job:

- touches only files under one or more `services/<name>/` or
  `platform-common/<name>/` — a matrix job builds each affected module with
  `./mvnw -pl <module> -am -amd clean verify` (also-make its dependencies so it
  compiles, also-make-dependents so a `platform-common` change is verified
  against every service that consumes it), while unrelated modules are skipped;
- touches anything shared — the parent POM, the Maven wrapper, the
  `platform-common` aggregator POM, `config-repo/`, or the workflow itself —
  falls back to a full reactor build, since a shared change can break anything.

A separate `security` job runs on every PR regardless: SpotBugs + FindSecBugs,
a hard fail. OWASP Dependency-Check does not run in CI (see
[SECURITY.md](SECURITY.md)) — run it locally before a dependency bump. The
`build` job is the single stable status check to require in branch protection;
it passes when whichever build path ran succeeded.

Adding a service or a `platform-common` module needs no CI change — the
`scope` job discovers both from the diff.

## Pull requests

- Branch off `main`. Keep a PR to one service or one shared change.
- `./mvnw clean verify` must pass. Don't disable a test or use `--no-verify` to
  get green — fix the cause.
- A decision that changes architecture gets an ADR in the same PR.
