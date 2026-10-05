# web

Pallet's dashboard and marketing site: a Next.js App Router application that is also the
backend-for-frontend. The browser talks only to this app's own origin; the app holds the session and
calls `api-gateway` on the browser's behalf, so no token ever reaches JavaScript. It is an independent
project with the same standing as a service under `services/`: its own manifest, lockfile, `Makefile`
and CI job, and nothing resolved from elsewhere in the repository.

Architecture and its rationale: [ADR-0023](../docs/adr/0023-web-app-architecture.md). Build plan:
`docs/workflows/web/`.

## Requirements

- Node `24.21.0` (`.nvmrc`; `nvm use` picks it up)
- pnpm `12.8.1` (pinned in `packageManager`; `corepack enable` installs it)
- Docker, for the integration tests

## Commands

```bash
cp .env.example .env.local     # local values only, git-ignored
make install                   # pnpm install --frozen-lockfile
make dev                       # http://localhost:5173
make verify                    # the single gate: typecheck, lint, format check, all tests, build
make help                      # every target
```

| Target                              | Runs                                                                                                                  |
| ----------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `make dev`                          | Dev server on port `5173` (Turbopack)                                                                                 |
| `make build`                        | Production build, `output: "standalone"`                                                                              |
| `make typecheck`                    | `next typegen` for typed routes, then `tsc --noEmit`                                                                  |
| `make lint`                         | ESLint, including the layer boundaries; zero warnings allowed                                                         |
| `make format` / `make format-check` | Prettier                                                                                                              |
| `make test`                         | Vitest `unit` and `component` projects                                                                                |
| `make test-int`                     | Vitest `integration` project (Docker required)                                                                        |
| `make e2e`                          | Playwright against a running app; with `PLAYWRIGHT_START_SERVER=1` it starts `pnpm start` itself (after `make build`) |
| `make verify`                       | `typecheck`, `lint`, `format:check`, `test`, `test:int`, `build`, stopping at the first failure                       |
| `make api-types`                    | Regenerate `openapi/*.json` and the API types from the running gateway (`PALLET_GATEWAY_URL`)                         |
| `make clean`                        | Build output, reports and caches                                                                                      |

The app runs on `5173` because that is the `DASHBOARD_BASE_URL` default the services already put in the
links they email, and `3000` belongs to Grafana. CI runs `pnpm verify` in the `web` job whenever
something under `web/` changes; Playwright needs the whole local stack and runs locally only.

## Configuration

Every server value is validated once, at boot (`src/shared/infrastructure/config/server-env.ts`): a missing
or malformed variable stops the server with its name and never surfaces later on a request. `.env.example`
lists them all with local values, except `PALLET_SESSION_ENCRYPTION_KEY`, which you generate yourself
(`openssl rand -base64 32`). Only `NEXT_PUBLIC_*` values reach the browser, and none of them is a secret.

## Session and the BFF

The browser never holds a token. Sign-in stores the Keycloak token pair in Redis, encrypted with
AES-256-GCM under `web:session:<sha256(id)>`, and gives the browser an opaque httpOnly cookie
(`pallet_session` locally, `__Host-pallet_session` on https). Every browser API call goes to `/bff/<service>/...`,
which checks the origin on writes, attaches the bearer token (refreshing it once, under a Redis lock, when it
is about to expire) and forwards an allow-listed set of headers to the gateway. A revoked or expired session
always surfaces as `401 SESSION_EXPIRED`. Design and rationale: [ADR-0025](../docs/adr/0025-web-bff-session.md).

Sign-in posts to `/api/session/login`, which calls identity with the client IP, replaces any session the
cookie already pointed at, and answers with the cookie and no token; identity's errors pass through
unchanged, `Retry-After` included. `/api/session/logout` revokes at identity, then deletes the session and
clears the cookie whatever identity answers, so signing out never gets stuck. Both, like every `/bff`
write, require `X-Pallet-Request: 1` and a same-origin request. A `?next=` is followed only when it names
a page on this site (`safeRedirect`); anything else lands on `/orgs`.

To rotate `PALLET_SESSION_ENCRYPTION_KEY`, give the new key a new `PALLET_SESSION_ENCRYPTION_KEY_ID` and move
the old one into `PALLET_SESSION_RETIRED_ENCRYPTION_KEYS` (`<id>:<key>,...`); existing sessions keep working
and are re-encrypted on their next write. `PALLET_TRUSTED_PROXY_HOPS` must equal the number of proxies in
front of the app that append to `X-Forwarded-For` (`0` locally: no client IP is forwarded).

## API contracts

Request and response types come from each service's OpenAPI spec, never written by hand. With the stack up,
`make api-types` fetches `/api/v1/<service>/v3/api-docs` through the gateway into `openapi/` and generates
`src/shared/infrastructure/api/generated/`. Both are committed, so a build never needs the backend; a diff
after regenerating means a contract changed, and the adapters that use it are reviewed in the same change.
Generated types never leave `infrastructure/`, and every response is still validated with Zod there.

## Layout

```
src/
├── app/              routes only: pages, layouts, route handlers
├── modules/<name>/   one per backend aggregate: domain/ application/ infrastructure/ presentation/ index.ts
├── shared/           the same four layers for the shared kernel
├── composition/      the only place adapters meet ports
└── test/             Vitest setup, MSW server and envelope factories, renderWithProviders
openapi/              api-docs snapshots the API types are generated from
e2e/                  Playwright specs
design/               static reference designs, never imported by the app
```

A module's presentation never imports `composition/`: it declares the use cases it needs with
`createUseCasesContext` (`shared/presentation/providers`), and `composition/client.tsx` provides them
(see `SessionActionsProvider`).

Dependencies point inward, and the build enforces it: `domain` and `application` import no React,
Next.js or infrastructure; `presentation` never imports `infrastructure`; another module is reachable
only through its `index.ts`. The full table is in `docs/workflows/web/00-README.md` §Layers and in
`eslint.config.mjs`.

| Test kind                      | Named           | Lives                |
| ------------------------------ | --------------- | -------------------- |
| Unit (node)                    | `*.test.ts`     | beside the code      |
| Component (jsdom)              | `*.test.tsx`    | beside the component |
| Integration (node, real Redis) | `*.int.test.ts` | `src/**/__int__/`    |
| End to end                     | `*.spec.ts`     | `e2e/`               |

Component and integration tests run against MSW with unhandled requests failing the test. Handlers answer
with `ok`, `page` and `error` from `src/test/msw/envelopes.ts`, the exact `ApiResponse`/`ErrorResponse`
shapes the services send, and `renderWithProviders` (`src/test/render.tsx`) mounts the real providers with a
fixed clock and a browser transport pointed at MSW. Integration tests share one Redis container per run, started by
`src/test/redis-global-setup.ts` outside MSW; on an SELinux host whose Docker socket the Testcontainers reaper
can't reach, run them with `TESTCONTAINERS_RYUK_PRIVILEGED=true`.

## Designs

`design/` is plain HTML, CSS and a small script, with no build step. Open `design/index.html` in a browser
to see every screen, grouped by where it sits and listed with the backend endpoints it uses.

```
design/
├── index.html          # every screen, the endpoints behind it, and the design tokens
├── assets/
│   ├── pallet.css      # tokens (light and dark) and every component
│   ├── pallet.js       # app shell, icons, theme toggle, menus, modals, preview states
│   ├── pallet-mark.svg # the logo mark as a vector
│   └── favicon.svg
└── pages/              # one file per screen
```

Each screen has a bar in the bottom corner for going back to the index, switching the theme and, where
a screen has them, flipping between its states (wrong password, expired link, disconnected repo, and
so on).

Colors come from the logo: `#2665FC` for actions and `#3AD98B` only for connected or done. Change a
token in `pallet.css` and it changes on every screen.

The sample data is made up: an organization called Kilima Labs, viewed by its owner.
