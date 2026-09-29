-- The unused-installation sweep uninstalls suspended installations too, so its index covers every status that can
-- still hold an unused clock (a DELETED row never does).
DROP INDEX git_integration.ix_installations_unused;
CREATE INDEX ix_installations_unused ON git_integration.installations (unused_since)
    WHERE unused_since IS NOT NULL AND status IN ('ACTIVE', 'SUSPENDED');
