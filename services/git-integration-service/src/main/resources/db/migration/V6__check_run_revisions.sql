-- A completed check run can still change its conclusion (a deploy failing after its build succeeded), which leaves
-- desired_state and last_reported_state equal. Every accepted change of the desired check run bumps
-- desired_revision; the reporter records the revision it delivered, so pending means the two differ. desired_phase
-- orders changes that keep the same state: a build finishing, then its deploy.
ALTER TABLE git_integration.check_runs
    ADD COLUMN desired_phase     VARCHAR(16) NOT NULL DEFAULT 'BUILD_STARTED'
        CHECK (desired_phase IN ('BUILD_STARTED', 'BUILD_FINISHED', 'DEPLOY')),
    ADD COLUMN desired_revision  INT         NOT NULL DEFAULT 1,
    ADD COLUMN reported_revision INT;

UPDATE git_integration.check_runs SET reported_revision = desired_revision
 WHERE last_reported_state IS NOT DISTINCT FROM desired_state;

ALTER TABLE git_integration.check_runs
    ADD CONSTRAINT ck_check_runs_commit_sha CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    ADD CONSTRAINT ck_check_runs_conclusion CHECK (
        (desired_state = 'completed') = (desired_conclusion IS NOT NULL)
        AND (desired_conclusion IS NULL OR desired_conclusion IN ('success', 'failure')));

DROP INDEX git_integration.ix_check_runs_pending;
CREATE INDEX ix_check_runs_pending ON git_integration.check_runs (next_attempt_at)
    WHERE reported_revision IS DISTINCT FROM desired_revision;
