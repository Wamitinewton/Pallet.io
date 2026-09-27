SET LOCAL lock_timeout = '5s';

ALTER TABLE org_team.organizations
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'PERSONAL' CHECK (kind IN ('PERSONAL', 'TEAM'));
ALTER TABLE org_team.organizations ALTER COLUMN kind DROP DEFAULT;

CREATE UNIQUE INDEX ux_organizations_owner_personal ON org_team.organizations (owner_user_id)
    WHERE kind = 'PERSONAL' AND status = 'ACTIVE';

CREATE INDEX ix_memberships_active_user ON org_team.memberships (user_id) WHERE status = 'ACTIVE';
