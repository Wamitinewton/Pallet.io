SET LOCAL lock_timeout = '5s';

-- Progress of the one-off org.membership.changed backfill, so a crashed run resumes where it stopped.
CREATE TABLE org_team.membership_backfill_cursor (
    id           SMALLINT    PRIMARY KEY CHECK (id = 1),
    last_org_id  VARCHAR(64) NOT NULL DEFAULT '',
    last_user_id VARCHAR(64) NOT NULL DEFAULT '',
    published    BIGINT      NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ
);
INSERT INTO org_team.membership_backfill_cursor (id) VALUES (1);
