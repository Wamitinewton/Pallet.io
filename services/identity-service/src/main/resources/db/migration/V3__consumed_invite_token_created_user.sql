-- The Keycloak user an invite created, or null when an existing account accepted it. Only an
-- account an invite created may be disabled when org-team-service rejects that invite.
ALTER TABLE identity.consumed_invite_tokens ADD COLUMN created_user_id VARCHAR(64);
