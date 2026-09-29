-- Consecutive rounds that failed because GitHub was unavailable. Drives the processor's backoff for that case
-- without spending an attempt, so an outage can never park a delivery.
ALTER TABLE git_integration.webhook_deliveries
    ADD COLUMN unavailable_streak INT NOT NULL DEFAULT 0;
