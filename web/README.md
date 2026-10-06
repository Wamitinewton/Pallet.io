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

## Signed-in shell and organization context

Everything under `(dashboard)` renders inside `DashboardShell`. Its layout resolves the session (none, or
expired, goes to `/login?reason=expired&next=...`; `proxy.ts` hands it the requested path in
`x-pallet-requested-path`, since layouts never see it) and prefetches the profile, my organizations and the
unread count in parallel. The open organization is only ever the `[orgId]` segment, and the caller's role only
`members/me`: `orgs/[orgId]/layout.tsx` reads both in parallel, answers `ORG_NOT_FOUND` and `NOT_A_MEMBER`
alike with `orgs/not-found.tsx` (a layout's `notFound()` is caught one segment up), and provides
`useOrgAccess()`. A screen decides whether to show a control with `useOrgAccess().can(capability)`, backed by
`shared/domain/permissions.ts`, which mirrors every `@PreAuthorize` in org-team-service and
git-integration-service; the backend's `403` stays the authority. `pallet_last_org` (not a secret) only picks
where `/orgs` lands. An empty organization list right after sign-up is the projection lag, not an error:
`/orgs` waits for it (`ProvisioningState`).

## Account and sessions

`/account` edits the display name, changes the password and lists the account's Keycloak sessions. A rename
lands in the profile query before identity answers, so the page and the user menu change together, and rolls
back on failure; members lists catch up when org-team-service projects `UserProfileUpdated`. A wrong current
password is `401 INVALID_CREDENTIALS`, which the BFF passes through as an answer about the request, never as a
revoked session. "This device" is the row whose id matches `keycloakSessionId` from `GET /api/session`; no
session id is ever drawn. Ending a session removes its row at once, and `SESSION_NOT_FOUND` counts as ended.
The `#security` fragment selects the password and sessions tab, including from the user menu while already on
the page.

## Organizations and step-up

`/orgs/[orgId]/settings` renames the organization (`org.rename`), lists every organization of mine a hundred at a
time, creates a team organization (also from the switcher) and deletes one (`org.delete`, team organizations
only). A rename lands in the organization query from the `PATCH` response and re-reads my list, so the switcher
follows. Deletion sends what was typed as `X-Confirm-Slug`; on `204` everything under `['org', orgId]` is
removed rather than invalidated, `pallet_last_org` is cleared if it pointed there, and the page leaves for
`/orgs`.

An owner action that needs a recent sign-in answers `403 REAUTHENTICATION_REQUIRED`; refreshing never helps,
since a refresh keeps the original `auth_time`. `useStepUp().runWithStepUp(action)` (`StepUpProvider`, mounted
once in `composition/client.tsx`) runs the action, and only on that code asks for the password and runs it
exactly once more; cancelling rejects with the original error. The password goes to
`/api/session/reauthenticate`, which signs in again as the session's email, refuses tokens for any other `sub`,
swaps them in under the same session id, then revokes the previous Keycloak session with the new bearer and the
old refresh token, so step-up never leaves a second session in the account's list.

## Members

`/orgs/[orgId]/members` keeps its list state in the URL (`q`, `role`, `status`, `sort`, `page`, `size`) through
one set of `nuqs` parsers that the page's server prefetch and the client both read, so the first paint and the
client ask for the same page. `member-list-query.ts` owns the backend's sort whitelist (an unknown `sort` is
dropped before any request), the page sizes, and the rule that `status=REMOVED` is only ever asked for by
someone with `members.viewRemoved`. Any filter change returns to page 1; the search reaches the URL 300ms after
typing stops. A row's menu offers exactly what `memberActions()` derives from `permissions.ts`. A role change
shows on every cached list at once and rolls back on refusal; removal refreshes the lists, the organization's
counts and teams; leaving drops everything under `['org', orgId]` and goes to `/orgs`; an ownership transfer
runs through step-up and re-reads `members/me`, so every control on screen follows the caller's new role.
Cache keys other modules invalidate (`['org', orgId, 'teams']`, my organizations) come from
`shared/presentation/query/keys.ts`, so no module imports another to refresh it. `MemberPicker` is the combobox
teams use to add people.

## Invites

The invites tab sits on the members page, but `invites` depends on `members` (the caller's access, and the
member directory that names whoever sent an invite), so `members` can't import it back. `MembersView` takes an
`invites` slot (the panel and the button beside the title) that the route page fills, and shows the tabs only
when `invites.manage` allows them, which is never in a personal organization. `InvitesProvider` owns the one
invite dialog, so the page's button and a row's "Invite again" share it. `InvitesTab` renders nothing for
anyone else, before any query hook runs, so a developer never sends a request that can only answer `403`.

URL state: the members page's `tab`, plus `inviteStatus` (`PENDING` by default, or `ACCEPTED`, `REVOKED`,
`EXPIRED`, `ALL`) and `invitePage`, named apart from the members list's `status` and `page`. A row offers
resend and revoke only while `isActionable()` holds by the injected clock, and only on invites whose role the
caller could grant (`canManageInvite()`, as `MembershipPolicy.checkCanInvite` checks resends and revokes).
Inviter names come from one read of up to a hundred active members, owners and admins first; anyone missing
reads as "A former member".

Create maps `ALREADY_A_MEMBER`, `MEMBER_PREVIOUSLY_REMOVED` and `INVITE_ALREADY_PENDING` onto the email field,
and offers to resend the pending invite for the last one; `QUOTA_EXCEEDED` and `PERSONAL_ORG_IMMUTABLE` aren't
about the address, so they show above the form. Resend updates the row in every cached list; a cooldown
`429` turns the row's button into a countdown from `Retry-After`, and `QUOTA_EXCEEDED` shows the backend's own
message, which names the limit. Revoke is optimistic on pending lists. When the invite was accepted, revoked
or had lapsed first, `revokeInvite` reports what it became instead of failing. No response carries the invite
token or link, so the dashboard never handles them.

### Accepting an invite

The emailed link opens `/invites/[token]`, a public page served with `Referrer-Policy: no-referrer`. The
page prefetches the preview without any session (`getPublicServerUseCases`), so a dead session can't fail
it, and the first paint already says what the invite is. `previewInvite` and both accept use cases answer
outcomes rather than throwing for a dead link: `INVALID_TOKEN` and `INVITE_NO_LONGER_VALID` read as
**expired** when the token's own `exp` has passed by the injected clock and as **withdrawn** otherwise. The
token's claims are read unverified, and only for that expiry and for the `orgId` to wait for after joining;
the invited address in it is never read.

A signed-out visitor sets a password (`POST /identity/invites/{token}/accept`) and goes to
`/login?joined=<orgName>`. A `409 CONFLICT` (the address already has an account) switches to signing in,
with `next` pointing back at the invite. A signed-in visitor joins as that account only when
`NEXT_PUBLIC_INVITE_EXISTING_ACCOUNT` is on, which waits for `identity-service/15`. Joining then polls my
organizations until the membership is projected, and `INVITE_EMAIL_MISMATCH` offers to sign out.

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
