-- Removed memberships are looked up by email too, which the partial ACTIVE-only unique index cannot serve.
CREATE INDEX ix_memberships_org_lower_email ON org_team.memberships (org_id, lower(email));

-- Serves the default member listing (joined_at, user_id) and every (org_id, status) lookup the old index served.
DROP INDEX org_team.ix_memberships_org_status;
CREATE INDEX ix_memberships_org_status_joined ON org_team.memberships (org_id, status, joined_at, user_id);

-- The invite list sorts by (created_at DESC, id DESC) with or without a status filter.
DROP INDEX org_team.ix_invites_org_status_created;
CREATE INDEX ix_invites_org_status_created ON org_team.invites (org_id, status, created_at DESC, id DESC);
CREATE INDEX ix_invites_org_created ON org_team.invites (org_id, created_at DESC, id DESC);

-- Soft-deleted apps are never listed; keeping them out of the index keeps it small as they accumulate.
DROP INDEX org_team.ix_apps_org_status;
CREATE INDEX ix_apps_active_created ON org_team.apps (org_id, created_at DESC, id) WHERE status = 'ACTIVE';
