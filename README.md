# Pallet

A self-built platform as a service, Connect a Git repo, push code, get back a live URL with TLS. Built in
public, one service at a time.

The full design brief is [`PROJECT.md`](PROJECT.md). Decisions and their
rationale are in [`docs/adr/`](docs/adr/README.md).

## Tech stack

- Java 21, Spring Boot 4.1. `platform-common/` is one self-contained Maven
  multi-module reactor (own POM, own Maven wrapper); every service under
  `services/<name>/` is its own fully independent Maven project (own POM, own
  Maven wrapper) that consumes `platform-common-*` as a published dependency —
  see [`CONTRIBUTING.md`](CONTRIBUTING.md) and [`PACKAGES.md`](PACKAGES.md)
- Apache Kafka event backbone (`spring-boot-starter-kafka`)
- Keycloak for identity — one realm, `org_id` claim on every token
- PostgreSQL for most services; ClickHouse for `audit-log-service` and
  `usage-metering-service`; Redis, MinIO, Vault alongside
- Micrometer + OpenTelemetry → Prometheus / Grafana / Jaeger
- Kubernetes for tenant workloads and the control plane; Resilience4j for every
  external call

See [ADR-0005](docs/adr/0005-java-21-spring-boot-4.md) for the Spring Boot 4
conventions (modular starters, Jackson 3, Testcontainers 2).

## Prerequisites

- JDK 21+ (the repo builds a Java 21 release; JDK 25 is fine)
- Docker + Docker Compose
- `kind` + `kubectl` for the deploy path (later phases)
- Maven is not required — every buildable directory (`platform-common/`, and
  each `services/<name>/`) bundles its own `./mvnw`

## Getting started

```bash
# 1. Start core infra (postgres, redis, kafka, keycloak, clickhouse)
make up

# 2. Run config-server — each service builds and runs independently, from its own directory
cd services/config-server
./mvnw spring-boot:run     # or: make run
# → http://localhost:8888/actuator/health
# → http://localhost:8888/config-server/default   (config it serves for itself)

# Optional: observability stack (prometheus :9090, grafana :3000, jaeger :16686)
make obs   # from the repo root
```

`make help` at the repo root lists infra targets only (docker-compose, kind, repo-wide format
check). `make help` inside `platform-common/` or any `services/<name>/` directory lists that
project's own build targets — each is independent of the others.

## Local endpoints

| Service | URL |
|---|---|
| config-server | http://localhost:8888 |
| Keycloak | http://localhost:8080 (admin / admin; realm `pallet`) |
| Postgres | localhost:5432 (pallet / pallet) |
| ClickHouse | http://localhost:8123 (pallet / pallet) |
| Kafka | localhost:29092 |
| Kafka UI | http://localhost:8090 (`make up-all` or `--profile ui`) |
| Prometheus / Grafana / Jaeger | :9090 / :3000 / :16686 (`make obs`) |
| MinIO / Vault | :9001 / :8200 (`make data`) |

## Layout

```
Makefile                    infra only: docker-compose, kind, repo-wide format check
platform-common/            self-contained Maven reactor — own pom.xml, own mvnw, own Makefile
  pom.xml                          pins Spring Boot, Spring Cloud, plugin versions for this reactor
  platform-common-events/          Kafka event contracts + topic catalog
  platform-common-security/        OAuth2 resource-server baseline + org_id check
  platform-common-observability/   metrics / tracing / logging deps every service pulls in
  ...                               (api, exception, messaging, resilience, test)
services/
  config-server/             Spring Cloud Config Server — own pom.xml, own mvnw, own Makefile
  notification-service/      reference event consumer — same: fully independent build
config-repo/                config config-server serves (git-backed later)
deploy/local/                prometheus, otel-collector, grafana, kind cluster
infra/keycloak/               realm export (clients, roles, org_id mapper)
docs/adr/                     architecture decision records
```

Every service under `services/` is a standalone Maven project with no parent POM shared with
`platform-common` or with any other service — see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Security scanning and reporting:
[`SECURITY.md`](SECURITY.md).

## License

[Apache-2.0](LICENSE).
