SET LOCAL lock_timeout = '5s';

ALTER TABLE org_team.outbox_events ADD COLUMN record_key VARCHAR(255);
ALTER TABLE org_team.outbox_events ADD COLUMN tombstone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE org_team.outbox_events ADD CONSTRAINT ck_outbox_events_tombstone
    CHECK (NOT tombstone OR (record_key IS NOT NULL AND payload IS NULL));
