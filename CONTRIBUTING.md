# Contributing to Pallet

Pallet is a build-in-public learning project. The premise is that other people
follow along and, once a service gets interesting, send pull requests. This
document is how the build fits together.

## The build: one shared reactor, independent services

Two different build shapes on purpose:

- `platform-common/` is one Maven multi-module reactor, self-contained: `platform-common/pom.xml`
  is parented directly to `spring-boot-starter-parent` and pins Spring Boot, Spring Cloud,
  Resilience4j, and every plugin version for the reactor beneath it. Eight modules:
  `platform-common-api` (response envelope + pagination shapes), `platform-common-exception`
  (`AppException` hierarchy + global handler), `platform-common-events` (Kafka event contracts +
  topic catalog), `platform-common-security` (resource-server baseline + `org_id` check),
  `platform-common-observability` (metrics/tracing/logging dependencies),
  `platform-common-messaging` (Kafka wiring, retry, DLT), `platform-common-resilience`
  (Resilience4j defaults), `platform-common-test` (composed Testcontainers test-slice
  annotations, test-scope only). It's published as versioned artifacts to GitHub Packages — see
  `PACKAGES.md`.
- Every service under `services/<name>/` is a **fully independent Maven project**: its own
  `pom.xml` (parented directly to `spring-boot-starter-parent`, not to anything in this repo),
  its own Maven wrapper (`services/<name>/mvnw`), its own SpotBugs exclude / OWASP suppression
  files, its own `Makefile`. No service shares a parent POM with `platform-common` or with any
  other service. A service consumes `platform-common-*` the same way an external repo would: a
  real GitHub Packages dependency, version pinned by hand in that service's own `pom.xml`
  (`<platform-common.version>`) — never a reactor sibling. The tradeoff this buys: a service
  builds, tests, and releases with zero knowledge of any sibling service, at the cost of some
  duplicated build config (compiler/Spotless/SpotBugs/OWASP plugin setup is copied into each
  service's `pom.xml` rather than defined once) — see `PACKAGES.md` for the mechanics and why.

Every service depends on `platform-common-observability` (which transitively brings Actuator, the
Prometheus registry, and OTLP export) and, once it serves tenant data, on
`platform-common-security`.

```bash
cd platform-common
./mvnw clean verify                              # platform-common reactor: unit + integration tests
./mvnw -P security verify                        # platform-common + SpotBugs / OWASP Dependency-Check

cd services/config-server
./mvnw clean verify                              # this service alone: unit + integration tests
./mvnw -P security verify                        # + SpotBugs / OWASP Dependency-Check
```

The root `Makefile` only wraps shared local infrastructure (`make up` / `make obs` /
`make kind-up`, ...) and repo-wide formatting (`make format-check`) — `make help` at the repo
root. `platform-common/` and each service directory carry their own `Makefile` with the same
target names (`build`, `test`, `verify`, `security`, `format-check`, `format`, and `run` for
services), scoped to that project: `make -C platform-common build`, `make -C services/config-server
build`, or `cd platform-common && make help`.

### Adding a new service

Copy an existing service directory (`pom.xml`, `mvnw`/`mvnw.cmd`/`.mvn/`, `Makefile`,
`spotbugs-exclude.xml`, `owasp-suppressions.xml`) as the starting point rather than writing a
`pom.xml` from scratch — it keeps the plugin versions and profile wiring consistent even though
nothing enforces that automatically anymore. CI discovers new services from the directory listing
under `services/` (see the `CI` section below), so no workflow change is needed there.

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
- Workflows (`deploy-orchestrator-service`, `billing-service` — the two services embedding a
  Temporal worker, per [ADR-0009](docs/adr/0009-temporal-for-saga-orchestration.md)): workflow and
  activity unit tests are `*WorkflowTest`, using the Temporal Java SDK's
  `TestWorkflowEnvironment`. A change to a workflow's shape also needs a replay test
  (`WorkflowReplayer` against a history captured from the previous version) before it merges —
  the same non-negotiable gate `./mvnw clean verify` already is for everything else, since a
  determinism break here surfaces as a stuck production saga, not a failed build.
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
  pre-commit install --hook-type pre-commit --hook-type pre-push
  ```
  Installing both hook types means a bad commit is caught at `git commit`,
  and `git push` re-checks anything that slipped through (e.g. a commit made
  with `--no-verify`) before it reaches `origin`. `make format-check` runs
  the same checks on demand (`pre-commit run --all-files`). They also run in
  CI (`style` job) regardless of whether the local hooks are installed.
- Java formatting (import order, unused imports, wrapping, brace placement)
  is [Palantir Java Format](https://github.com/palantir/palantir-java-format)
  via the Spotless Maven plugin, not `.editorconfig` — editorconfig only
  covers whitespace-level rules, not language formatting. `mvn spotless:check`
  is bound to the `verify` phase, so it runs on every `./mvnw clean verify` /
  CI build without any extra flag, in `platform-common` and in every service
  alike (each service's own `pom.xml` carries the same plugin config — see
  "The build" above). `make -C platform-common format` or `make -C
  services/<name> format` (equivalently, `cd` into either and run `make
  format`) fixes that project's own drift — there is no repo-wide `format`
  target, since there's no repo-wide POM left to run it against. Locally,
  `.pre-commit-config.yaml` has one `spotless-check-*` hook per project
  (`platform-common`, `config-server`, `notification-service`), each scoped by
  path so only a `.java` file under that project's own directory triggers it;
  adding a new service needs a matching hook added by hand, same as the CI
  `security-service`/`service` matrix jobs need no such addition but this file
  does. CI's `style` job explicitly skips all of these
  (`SKIP: spotless-check-...`) since each project's own `verify` already runs
  `spotless:check` — the pre-commit hooks exist purely for fast local
  feedback, not as CI's actual enforcement.

## CI

`.github/workflows/build.yml` is path-filtered, for both a PR and a push to
`main`. Either is classified the same way by the `scope` job, diffing against
the PR's base SHA or the push's previous SHA:

- a change under `platform-common/<name>/` only — a matrix job builds each
  affected `platform-common` module, from inside `platform-common/` with its
  own wrapper (`./mvnw -pl <module> -am -amd clean verify`: also-make its
  dependencies so it compiles, also-make-dependents so the change is verified
  against every `platform-common` module downstream of it), while unrelated
  `platform-common` modules are skipped;
- a change under `services/<name>/` — since every service is now a fully
  independent Maven project, a matrix job builds *only* that service, from
  inside its own directory with its own wrapper (`cd services/<name> &&
  ./mvnw clean verify`) — there is no `-am`/`-amd` step and no other service
  is touched, because none of them share anything to also-make;
- a change to `platform-common/pom.xml`, `platform-common`'s own Maven
  wrapper, its SpotBugs/OWASP files, or `build.yml` itself — falls back to a
  full `platform-common` reactor build plus every service. Nothing plays this
  role for services any more: there is no file left whose change can affect
  more than one service's build;
- no usable base commit to diff against (the push's previous SHA is unset,
  the zero SHA, or unreachable — a new branch, first push, or force push) —
  same full-build fallback, since there's nothing safe to diff.

Two separate `security` jobs mirror that scoping — SpotBugs + FindSecBugs, a
hard fail. `security-common` runs whenever any `platform-common` module (or
the whole reactor) is in scope; `security-service` matrixes over just the
affected services, since each now runs its own SpotBugs/OWASP profile against
its own exclude/suppression files. Neither runs when nothing in its scope
changed. OWASP Dependency-Check does not run in CI (see
[SECURITY.md](SECURITY.md)) — run it locally before a dependency bump. The
`build` job is the single stable status check to require in branch protection;
it passes when every build path that actually ran succeeded, and a skipped
job (nothing in its scope changed) counts as a pass.

Adding a service or a `platform-common` module needs no CI change — the
`scope` job discovers both from the directory listing and the diff.

## Pull requests

- Branch off `main`. Keep a PR to one service or one shared change.
- `./mvnw clean verify` must pass — inside `platform-common/` for a
  `platform-common` change, inside the service's own directory
  (`cd services/<name> && ./mvnw clean verify`) for a service change. Don't
  disable a test or use `--no-verify` to get green — fix the cause.
- A decision that changes architecture gets an ADR in the same PR.
