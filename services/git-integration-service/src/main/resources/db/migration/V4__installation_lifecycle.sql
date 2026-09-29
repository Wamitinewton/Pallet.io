-- The periodic repository sync walks installations oldest-synced first. Any completed listing counts, a 304 included,
-- so an unchanged installation still moves to the back of the queue.
ALTER TABLE git_integration.installations
    ADD COLUMN repositories_synced_at TIMESTAMPTZ;
CREATE INDEX ix_installations_sync_due ON git_integration.installations (repositories_synced_at NULLS FIRST)
    WHERE status = 'ACTIVE';

-- installation_repositories.added lists no default branch; the row carries none until the next sync fills it.
ALTER TABLE git_integration.installation_repositories
    ALTER COLUMN default_branch DROP NOT NULL;
