-- The trace of the build or deploy event that last changed the desire, continued by the reporter's GitHub write.
ALTER TABLE git_integration.check_runs ADD COLUMN traceparent VARCHAR(64);
