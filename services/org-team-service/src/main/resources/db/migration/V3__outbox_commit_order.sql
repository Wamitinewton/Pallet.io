SET LOCAL lock_timeout = '5s';

-- Outbox ids are assigned at insert, not at commit, so a transaction that appended first can commit last.
-- tx_id records the writer's transaction; the relay only reads rows older than every still-open
-- transaction, which makes (tx_id, id) a commit-safe delivery order (ADR-0017).
-- Existing rows all take this migration's transaction id, so they keep id order among themselves.
ALTER TABLE org_team.outbox_events ADD COLUMN tx_id xid8 NOT NULL DEFAULT pg_current_xact_id();

DROP INDEX org_team.ix_outbox_pending;
DROP INDEX org_team.ix_outbox_org_open;
CREATE INDEX ix_outbox_pending ON org_team.outbox_events (tx_id, id) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_org_open ON org_team.outbox_events (org_id, tx_id, id) WHERE status <> 'PUBLISHED';
