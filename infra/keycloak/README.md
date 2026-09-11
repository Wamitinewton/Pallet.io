# Keycloak realm — `pallet`

`pallet-realm.json` is the source of truth for the platform's single Keycloak realm
(`docs/adr/0003-single-keycloak-realm-multi-tenancy.md`). `docker-compose.yml` mounts this
directory into the Keycloak container and starts it with `--import-realm`, which imports the file
on boot if the realm doesn't already exist in the container's Postgres-backed store. A manual
change made through the admin console only lives in that container's database until it's exported
back into this file and committed — the console is for iterating, the file is what every other
environment (CI, another developer's machine, Testcontainers) actually gets.

## Re-exporting after a manual change

1. Make the change through the running admin console (`make up`, then
   `http://localhost:8080/admin`, `admin` / `admin`).
2. Stop the Keycloak container without dropping its data:
   ```
   docker compose stop keycloak
   ```
3. Export the realm, with users embedded in the same file (matching this file's current shape,
   rather than split into a separate `pallet-users-0.json`):
   ```
   docker compose run --rm keycloak export --dir /opt/keycloak/data/import --realm pallet --users realm_file
   ```
4. Diff `infra/keycloak/pallet-realm.json` before committing. Keycloak's exporter writes resource
   `id`s and a `parentId` on every entry it touches — strip those for anything you hand-authored
   (this file otherwise omits `id`s throughout and lets Keycloak assign them on import), and check
   that the export didn't silently drop the `components` block below (the exporter includes it, but
   it's easy to miss in a large diff).
5. Restart normally: `docker compose up -d keycloak`.

## Why the realm defines every default client scope explicitly

`clientScopes` below includes `roles`, `profile`, `email`, `web-origins`, `basic`, and
`offline_access` alongside the custom `org` scope — not just `org`. Keycloak only auto-seeds its
built-in default scopes when a realm import omits the `clientScopes` key entirely; the moment a
realm defines its own `clientScopes` array (needed here for `org`), that array becomes the
*complete* scope list Keycloak imports, and everything else is silently dropped. The
first version of this file defined only `org` and, as a result, imported a realm whose access
tokens carried no `sub`, `realm_access.roles`, or `preferred_username` claim at all — verified
directly against a running import before this was caught. Every scope here is the standard
Keycloak definition for that name (mappers included) so removing this file's dependency on
built-in seeding doesn't change token shape from a stock realm, plus `org` for the `org_id` claim.

## `org_id`: required attribute, not just a convention

The realm's `components` block configures Keycloak's declarative User Profile so that `org_id` is
a **required** user attribute whenever a user is created or edited through an admin-context caller
(`"required": {"roles": ["admin"]}` — the context every Keycloak Admin API call runs under,
including `identity-service`'s own service account). This was verified against a live import: an
Admin API `POST /users` without an `org_id` attribute is rejected with `400` /
`error-user-attribute-required` before `identity-service`'s own sign-up code ever gets a chance to
enforce it. It's edit-only for the `admin` role, never `user` — nothing lets an authenticated user
change their own `org_id` through a self-service profile edit, which would otherwise be a
tenant-hijack path (`docs/identity-service/ARCHITECTURE.md`'s single-org-per-account invariant).

## Token and session lifetimes

| Setting | Value | Reasoning |
|---|---|---|
| `accessTokenLifespan` | 900s (15 min) | Bounds how stale a just-changed realm role can be on an already-issued access token — `docs/org-team-service/ARCHITECTURE.md` §Authorization model cites this exact number as the window a demoted user's old token stays valid for. Changing it here means updating that document's assumption too. |
| `ssoSessionIdleTimeout` | 1800s (30 min) | How long an inactive session (and its refresh token) stays valid. Short enough that an abandoned browser tab or a stolen refresh token has a bounded window; long enough that a normal work session doesn't force re-login mid-task. |
| `ssoSessionMaxLifespan` | 36000s (10 h) | Hard ceiling on a session regardless of activity — roughly a working day, so a developer isn't forced to re-authenticate mid-shift, but a session can't be silently kept alive indefinitely by background refresh traffic. |

## Realm roles

Four realm roles, composite: `viewer` ⊂ `developer` ⊂ `admin` ⊂ `owner`. A token carrying `owner`
has `admin`, `developer`, and `viewer` in its effective role set automatically (Keycloak expands
composites when building `realm_access.roles`), so a single `hasRole("owner")` check downstream
also satisfies any `hasAnyRole("admin", "developer", "viewer")` check —
`docs/org-team-service/ARCHITECTURE.md` §Authorization model depends on this chain.

## Per-service confidential clients

Only `identity-service` has a Keycloak Admin API credential this phase — the platform's only
service allowed to create, disable, or role-update a Keycloak user
(`docs/identity-service/ARCHITECTURE.md` §The boundary problem). Its service account is granted
exactly `realm-management.manage-users`, not `realm-admin` — verified live that this scope is
sufficient for user CRUD and session management but rejects client creation (`403`) and realm
settings changes. `config-server` and `deploy-orchestrator-service` have client-credentials clients
for their own token needs but no Admin API role.

## Dev-only values

`sslRequired: none`, the client secrets (`*-dev-secret`), and the seeded `dev-owner` /
`org_local_dev` user exist only for local development and CI Testcontainers runs — none of this is
appropriate for a deployed environment, and nothing here should be copied into one without
generating real secrets first.
