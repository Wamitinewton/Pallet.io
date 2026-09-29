-- The tables platform-common-outbox reads and writes (ADR-0016, ADR-0017, ADR-0020).
-- A service copies these statements into its own Flyway migration, qualified with its schema.
-- Table names are unqualified here so the statements apply to whichever schema is current.
-- OutboxSchemaAssertions (platform-common-test) compares a service's tables against this file.

CREATE TABLE outbox_events (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id         UUID          NOT NULL UNIQUE,
    org_id           VARCHAR(64)   NOT NULL,
    event_type       VARCHAR(64)   NOT NULL,
    payload          JSONB,
    sensitive        BOOLEAN       NOT NULL DEFAULT false,
    traceparent      VARCHAR(64),
    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'PARKED')),
    attempts         INT           NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_error       VARCHAR(500),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,
    tx_id            xid8          NOT NULL DEFAULT pg_current_xact_id(),
    record_key       VARCHAR(255),
    tombstone        BOOLEAN       NOT NULL DEFAULT false,
    CONSTRAINT ck_outbox_events_tombstone CHECK (NOT tombstone OR (record_key IS NOT NULL AND payload IS NULL))
);
CREATE INDEX ix_outbox_pending ON outbox_events (tx_id, id) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_org_open ON outbox_events (org_id, tx_id, id) WHERE status <> 'PUBLISHED';
CREATE INDEX ix_outbox_published_at ON outbox_events (published_at) WHERE status = 'PUBLISHED';

CREATE TABLE processed_events (
    event_id      UUID         NOT NULL,
    consumer      VARCHAR(64)  NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
CREATE INDEX ix_processed_events_at ON processed_events (processed_at);
