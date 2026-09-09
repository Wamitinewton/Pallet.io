# Testing Pallet

This is the guide for how tests get written across Pallet. The short version: every
service leans on `platform-common-test` instead of rolling its own Testcontainers setup, mocks
are for collaborators you own, not for Postgres or Kafka, and a test's name tells you which
Maven plugin runs it before you even open the file.

If you're adding a new service, add `platform-common-test` as a `test`-scope dependency and
read the annotation Javadoc — that's the source of truth this document is built from. This file
is the map; the annotations are the territory.

## The four kinds of test

Every test in this codebase is one of these. Pick based on what you're actually trying to
prove, not habit.

| Annotation | What it boots | When to reach for it |
|---|---|---|
| `@UnitTest` | Nothing — no Spring context, no container. Mockito only. | Testing a single class's logic in isolation: a mapper, a validator, a service method with its collaborators mocked. |
| `@ControllerTest` | A `@WebMvcTest` slice, no database, no broker. | Testing request/response shape, validation, and error rendering for one controller. |
| `@RepositoryTest` | A `@DataJpaTest` slice against a real, singleton Postgres container. | Testing a repository method, a native query, JSONB mapping — anything where the database's actual behaviour matters. |
| `@IntegrationTest` | A full application context, random port, real Postgres *and* Kafka. | End-to-end: an event comes in, gets processed, lands in the database, and the read API reflects it. |
| `@MessagingIntegrationTest` | A full context with real Kafka but no Postgres. | Proving a `@KafkaListener` consumes and dedupes correctly, without paying for a datasource the test doesn't need. |

If you're not sure which one fits, ask what would have to be real for the test to catch a real
bug. If the answer is "nothing, it's pure logic," it's a unit test. If the answer includes
"the database" or "the broker," reach for the slice or integration annotation that gives you
the real thing.

## Naming decides who runs your test

Surefire runs `*Test`. Failsafe runs `*IntegrationTest`, during `mvn verify`, after the
`package` phase. This is not a style preference — it's wired into every buildable POM in this
repo (`platform-common`'s and every service's own), and the naming is how the build tells the
two plugins apart. Get it wrong and your test either doesn't run in CI, or runs at the wrong
phase and blocks a build it shouldn't.

- `@UnitTest`, `@ControllerTest`, `@RepositoryTest` classes are named `*Test`.
- `@IntegrationTest`, `@MessagingIntegrationTest` classes are named `*IntegrationTest`.

`./mvnw test` gives you fast feedback (unit + slice tests, no containers). `./mvnw verify` runs
everything, containers included, and is the bar a PR has to clear.

## Don't mock what the container is there to test

If a container exists for something — Postgres, Kafka, Redis, Keycloak — the point of the
integration or repository test is to hit the real thing. Mocking a `Repository` in a
`@RepositoryTest`, or a `KafkaTemplate` in a `@MessagingIntegrationTest`, defeats the reason
that test exists. The failure modes that matter here — a JSONB column that doesn't round-trip,
a unique constraint that doesn't fire, a listener that double-processes on redelivery — only
show up against the real thing. This is called out in `CONTRIBUTING.md` for a reason: it's the
whole value of paying the container startup cost.

Mock freely at the unit test level, where you're testing your own logic and the collaborator
is someone else's already-tested code.

## Containers are shared, not per-class

Every `@RepositoryTest` and `@IntegrationTest` in the same test run shares one Postgres
container; every Kafka-touching test shares one Kafka container. They start lazily on first
use and are never explicitly stopped — Ryuk cleans them up when the JVM exits. You don't start
or stop anything yourself, and you shouldn't try to. If you find yourself reaching for
`@Testcontainers` or a `@Container` field directly, stop — that's almost always a sign you
should be using the shared configuration instead, not adding a second container of the same
kind.

Two things follow from this:

- **Don't rely on state from another test class.** Containers are shared, but each
  `@RepositoryTest` method still runs in its own rolled-back transaction. Integration tests
  don't get that for free — write them so they clean up after themselves or use data that
  won't collide with anything else running in the same fork.
- **Image versions are overridable, not hardcoded into your test.** If you need a different
  Postgres or Kafka version for a one-off compatibility check, that's a system property
  (`-Dpallet.test.postgres.image=...`), not a change to the shared configuration.

Redis and Keycloak aren't bundled into `@IntegrationTest` by default, because not every
service touches them. If your service does, `@Import` the relevant configuration
(`RedisTestContainerConfiguration`, `KeycloakTestContainerConfiguration`) on top of
`@IntegrationTest` yourself.

## Asserting on the platform's response envelopes

Every endpoint returns one of three shapes: `ApiResponse`, `PageResponse`, or `ErrorResponse`.
Don't write `jsonPath` chains or manual field checks for these — use the assertions built for
them:

```java
import static io.pallet.common.test.assertions.PalletAssertions.assertThat;

assertThat(response).isSuccess().data().isEqualTo(expected);
assertThat(errorResponse).isFailure().hasErrorCode("NOT_FOUND").hasStatus(HttpStatus.NOT_FOUND);
assertThat(pageResponse).isFirstPage().content().hasSize(3);
```

Static-import `assertThat` from `PalletAssertions` alongside AssertJ's own — overload
resolution sorts out which one applies based on the argument type. If you're asserting on a
raw MockMvc JSON body instead of a deserialized object, that's a sign to deserialize it first
(`JsonTestSupport.fromJson`) rather than hand-writing `jsonPath` for a shape that already has
an assertion.

## Testing secured endpoints

`@ControllerTest` disables servlet filters by default, Spring Security included. This is
deliberate — it means a service where only *some* controllers are secured doesn't have every
other controller test start 401ing the moment the security starter lands on the classpath.

If you're specifically testing a secured controller's authorization rules, turn filters back
on and stamp the request with a mock JWT rather than reaching for a real Keycloak token:

```java
@ControllerTest(SecuredController.class)
@AutoConfigureMockMvc(addFilters = true)
class SecuredControllerTest {

    @Test
    void aRequestWithTheAdminRoleIsAllowed() throws Exception {
        mvc.perform(get("/secured/thing").with(orgJwt("org-1", "admin")))
                .andExpect(status().isOk());
    }
}
```

`orgJwt` sets both the `org_id` claim and the matching `ROLE_*` granted authorities, so a
`hasRole("admin")` check on the controller under test sees exactly what a real token would
produce. This only makes sense for a service that already depends on
`platform-common-security` — it's an optional piece of `platform-common-test`, not something
every service pulls in.

## Writing the test itself

- **Name the method after the behaviour, not the mechanism.** `dataFailsWhenTheResponseIsNotSuccessful`
  tells you what's being proven; `test3` or `dataTest` doesn't. Read the test suite for
  `PalletAssertions` if you want a feel for the level of specificity to aim for.
- **One behaviour per test.** If you need "and" to describe what a test method checks, it's
  probably two tests.
- **Set up only what the test needs.** A `@RepositoryTest` that touches one table shouldn't
  seed data for three others "just in case."
- **Prefer the platform's own fixtures over new abstractions.** Before writing a test helper,
  check whether `platform-common-test` already has one. A second, slightly different way to
  stamp a mock JWT or assert on an `ErrorResponse` is a maintenance cost, not a convenience.
- **A comment in a test earns its place the same way it does anywhere else** — only when it
  explains a non-obvious constraint (why a value has to be exactly this, why a step is ordered
  this way), never to narrate what the assertion below it already says.

## Local setup

Tests that touch a container need Docker (or a Testcontainers-compatible runtime) running
locally. `./mvnw test` never needs it — that's unit and slice tests only. `./mvnw verify` does.
CI runs both on every PR.

```bash
./mvnw test                              # fast: unit + slice tests, no containers
./mvnw verify                            # full: adds Failsafe + real containers

# a single service is a fully independent Maven project - run its own wrapper from its own directory
cd services/notification-service && ./mvnw verify
```

If a container-backed test is slow to start locally, that's almost always Docker pulling an
image for the first time — after that first pull, the singleton pattern means every test class
in the same run reuses the same running container.
