-- A revoked GitHub authorization belongs to a user; its audit record fans out to that user's orgs.
CREATE INDEX ix_org_memberships_user ON git_integration.org_memberships (user_id) WHERE status = 'ACTIVE';
