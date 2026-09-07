# 5. Java 21 and Spring Boot 4.1

- Status: accepted
- Date: 2026-09-07

## Context

The brief was written against Spring Boot 3.x. Spring Boot 4.1 is now GA and is
the version this project builds on. It changes several conventions that affect
every module, so they are worth recording once.

## Decision

**Java 21** as the language level (`maven.compiler.release=21`), current LTS. The
build machine ships JDK 25, which compiles a 21 release without issue. Moving the
target to 25 later is a one-line change in the parent POM plus a CI matrix bump.

**Spring Boot 4.1.0** via `spring-boot-starter-parent`, with **Spring Cloud
2025.1.2** (Oakwood) — Spring Cloud 2025.1.1 and earlier do not run against Boot
4.1.

Conventions that follow from Boot 4:

- **Modular starters.** `spring-boot-starter-web` → `spring-boot-starter-webmvc`;
  `spring-boot-starter-oauth2-resource-server` →
  `spring-boot-starter-security-oauth2-resource-server`;
  `spring-boot-starter-aop` → `spring-boot-starter-aspectj`. Test slices are
  their own starters (`spring-boot-starter-webmvc-test`, etc.) and each pulls in
  `spring-boot-starter-test` transitively.
- **Jackson 3.** Databind types move from `com.fasterxml.jackson` to
  `tools.jackson`; annotations (`@JsonInclude`, `@JsonProperty`) stay under
  `com.fasterxml.jackson.annotation`. `@JsonComponent` → `@JacksonComponent`.
- **Testcontainers 2.x** artifact ids all carry a `testcontainers-` prefix
  (`testcontainers-postgresql`, `testcontainers-kafka`, `testcontainers-junit-jupiter`).
- Kafka is consumed through `spring-boot-starter-kafka`, not raw `spring-kafka`.

## Consequences

- The `PROJECT.md` tech-stack section is updated to say Boot 4.1 / Java 21 and to
  use the modular starter names.
- Resilience4j's Spring Boot 4 starter compatibility must be confirmed at first
  use; the BOM is imported in the parent but nothing depends on it yet.
- Contributors copying Spring Boot 3 snippets from the web will hit the renamed
  starters and Jackson packages — called out in `CONTRIBUTING.md`.
