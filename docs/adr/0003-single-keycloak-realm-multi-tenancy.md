# 3. One Keycloak realm, org_id claim on every token

- Status: accepted
- Date: 2026-09-07

## Context

Pallet is multi-tenant. Keycloak supports realm-per-tenant, but that becomes
unwieldy past a few hundred tenants (realm creation, key management, admin API
fan-out). Tenants need clean data and log isolation regardless of the model.

## Decision

One Keycloak realm for the whole platform. Every access token carries an
`org_id` claim (a Keycloak client scope, `org`, maps a user attribute into the
token — see `infra/keycloak/pallet-realm.json`). Every service that touches
tenant data checks `org_id` against the resource being requested;
`platform-common-security` provides `OrgContext.requireOrgId()` and a
secure-by-default resource-server filter chain.

Org roles (owner, admin, developer, viewer) are realm roles surfaced as
`ROLE_*` authorities. Service-to-service calls use the client-credentials grant
with a dedicated Keycloak client per service.

Realm-per-org stays available as a heavier isolation option if a customer ever
needs it contractually.

## Consequences

- One place to manage identity; tenant isolation is an application-level claim
  check, not an infrastructure boundary — that check must be present on every
  tenant-data path, and is worth a dedicated test per service.
- The same `org_id` maps to one Kubernetes namespace per org, which is where
  `ResourceQuota`, RBAC, and `NetworkPolicy` attach.
