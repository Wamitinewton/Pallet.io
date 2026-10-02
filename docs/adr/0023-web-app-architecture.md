# 23. Web app architecture

- Status: accepted
- Date: 2026-10-02

## Context

`web/` turns the static designs in `web/design/` into the dashboard and marketing site, against the
services that exist today behind `api-gateway`. It is the first client a person uses, so it is also the
first end-to-end test of every backend contract. Four forces shape it:

- **Tokens must never reach JavaScript.** ADR-0015 makes sessions revocable platform-wide, and an access
  or refresh token in browser storage is readable by any script that runs on the page. Something
  server-side has to hold the tokens and attach them per request.
- **The org is always a path parameter** (ADR-0018), and every tenant read is per session. Nothing
  tenant-specific can sit in a shared cache.
- **The backend contracts are strict and documented** (ADR-0014): each service publishes OpenAPI, and
  every endpoint answers `ApiResponse`/`ErrorResponse` with documented error codes.
- **Twenty feature checkpoints will be built one at a time** (`docs/workflows/web/`). Without a structure
  the build enforces, the first shortcut becomes the pattern the next nineteen copy.

## Decision

1. **One Next.js App Router deployable is both the dashboard and the backend-for-frontend.** Server
   Components prefetch for first paint; route handlers hold the session (encrypted in Redis, keyed by
   an httpOnly cookie, ADR-0024) and proxy `/bff/*` to the gateway with the bearer token attached. The
   browser talks only to its own origin. `output: "standalone"`, strict TypeScript
   (`noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`), React Compiler and typed routes on.
2. **Clean architecture with feature modules that mirror the backend aggregates.** `src/modules/<name>/`
   holds `domain/`, `application/`, `infrastructure/`, `presentation/` and a public `index.ts`;
   `src/shared/` holds the same four layers for the kernel; `src/composition/` is the only place
   adapters meet ports; `src/app/` holds routes only. Dependencies point inward, per the table in
   `docs/workflows/web/00-README.md` §Layers.
3. **The layer table is a lint error, not a convention.** `eslint-plugin-boundaries`
   (`boundaries/dependencies`, `default: "disallow"`) classifies every file by path and encodes each
   layer's allowed imports; another module is reachable only through its `index.ts`. `import/no-cycle`,
   `no-restricted-imports` (no React, Next.js or `server-only` in `domain`/`application`, no generated
   API types outside `infrastructure`) and `no-restricted-globals` (no global `fetch` outside
   `shared/infrastructure`) close the gaps the path rules don't see. `make verify` fails on a violation.
4. **TanStack Query is the only server-state cache, and there are no Server Actions for mutations.**
   Reads prefetch on the server and hydrate into the same query options on the client; writes go through
   a use case and `useMutation`, then invalidate keys that are hierarchical by org.
5. **CSS Modules over the existing tokens.** `web/design/assets/pallet.css` already is the design
   system, as CSS custom properties for light and dark; it is ported, not re-expressed.
6. **Request and response types are generated per service** from each `/v3/api-docs` with
   `openapi-typescript`, called through `openapi-fetch`, and never leave `infrastructure/`, where they
   are mapped to domain types.
7. **A test pyramid with the services' posture.** Vitest unit tests for rules, use cases and mapping;
   Vitest + Testing Library + MSW component tests that drive each screen's states from real response
   envelopes; Vitest + Testcontainers Redis integration tests for the session and proxy; Playwright +
   axe end to end against the real local stack. The first three run in CI through `pnpm verify`;
   Playwright needs the whole stack and runs locally.
8. **`web/` is an independent project**, like a service under `services/`: its own `package.json`,
   committed `pnpm-lock.yaml`, pinned Node (`.nvmrc`, `engines`) and pnpm (`packageManager`), exact
   dependency versions, its own `Makefile` and its own path-filtered CI job.

## Consequences

- A token, refresh token or session id never reaches browser storage or a script; the cost is that
  every browser read is one extra hop through the BFF, and the BFF needs Redis.
- Server prefetch and client cache share one set of query options, so a page has data on first paint
  without a second fetch, and there is exactly one write path to test.
- A misplaced import fails the build with a message naming both layers. The price is ceremony for small
  features: a domain type, a port, an adapter and a view model even where one would do.
- A backend contract change surfaces as a type error after `api:types`, not as a runtime surprise. The
  OpenAPI snapshots under `web/openapi/` must be refreshed when a service changes its API.
- Restyling a token in `pallet.css` restyles the app, but there is no utility-class vocabulary; layout
  lives in each component's module.
- Follow-up: ADR-0024 records the session and proxy design (checkpoint 04).

## Alternatives considered

- **A Vite SPA with a separate BFF service.** Two deployables and two pipelines for one product surface,
  and no server prefetch: every page paints empty, then fetches.
- **Server Actions for mutations.** A second write path beside TanStack Query, with its own cache
  invalidation, harder to test in isolation from the framework and with no shared cache semantics with
  the reads.
- **Tailwind.** Re-expresses a design system that already exists as tokens and component CSS, and turns
  every screen port into a translation exercise.
- **Redux Toolkit Query.** Heavier, carries a store the app otherwise doesn't need, and fits less
  naturally with Server Component prefetch and hydration.
- **`boundaries/element-types` and `boundaries/entry-point`**, as first sketched. Deprecated in
  `eslint-plugin-boundaries` 7 in favor of the single `boundaries/dependencies` rule, which expresses
  both the layer table and the entry-point rule (another module's files are reachable only as its
  `index.ts`).
