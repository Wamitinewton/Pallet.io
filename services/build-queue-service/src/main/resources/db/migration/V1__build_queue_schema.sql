CREATE SCHEMA IF NOT EXISTS build_queue;

CREATE TABLE build_queue.apps (
    app_id      TEXT         PRIMARY KEY,
    org_id      TEXT         NOT NULL,
    slug        TEXT,
    status      TEXT         NOT NULL CONSTRAINT ck_apps_status CHECK (status IN ('ACTIVE', 'DELETED')),
    deleted_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_apps_org_app UNIQUE (org_id, app_id)
);

CREATE TABLE build_queue.orgs (
    org_id          TEXT         PRIMARY KEY,
    status          TEXT         NOT NULL CONSTRAINT ck_orgs_status CHECK (status IN ('ACTIVE', 'DELETED')),
    last_served_at  TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE build_queue.org_memberships (
    org_id          TEXT    NOT NULL,
    user_id         TEXT    NOT NULL,
    role            TEXT    NOT NULL,
    status          TEXT    NOT NULL CONSTRAINT ck_org_memberships_status CHECK (status IN ('ACTIVE', 'REMOVED')),
    source_version  BIGINT  NOT NULL,
    CONSTRAINT pk_org_memberships PRIMARY KEY (org_id, user_id)
);

CREATE TABLE build_queue.builds (
    build_id             UUID         PRIMARY KEY,
    org_id               TEXT         NOT NULL,
    app_id               TEXT         NOT NULL,
    branch               TEXT         NOT NULL,
    commit_sha           TEXT         NOT NULL CONSTRAINT ck_builds_commit_sha CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    push_event_id        UUID         NOT NULL CONSTRAINT ux_builds_push_event_id UNIQUE,
    trigger              TEXT         NOT NULL CONSTRAINT ck_builds_trigger
                                          CHECK (trigger IN ('WEBHOOK', 'MANUAL', 'LINKED', 'RECONCILED')),
    priority_class       SMALLINT     NOT NULL CONSTRAINT ck_builds_priority_class CHECK (priority_class IN (0, 1)),
    provider             TEXT         NOT NULL,
    installation_id      BIGINT       NOT NULL,
    repo_id              BIGINT       NOT NULL,
    repo                 TEXT         NOT NULL,
    root_directory       TEXT,
    head_commit_message  TEXT,
    head_commit_author   TEXT,
    status               TEXT         NOT NULL CONSTRAINT ck_builds_status
                                          CHECK (status IN ('QUEUED', 'DISPATCHED', 'RUNNING', 'SUCCEEDED', 'FAILED',
                                                            'SUPERSEDED', 'CANCELLED', 'REJECTED', 'EXPIRED')),
    status_reason        TEXT,
    superseded_by        UUID,
    attempt              SMALLINT     NOT NULL DEFAULT 0,
    enqueued_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    dispatched_at        TIMESTAMPTZ,
    start_deadline       TIMESTAMPTZ,
    started_at           TIMESTAMPTZ,
    lease_expires_at     TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    cancelled_by         TEXT,
    traceparent          TEXT,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_builds_app FOREIGN KEY (org_id, app_id) REFERENCES build_queue.apps (org_id, app_id),
    -- Deferred so intake can supersede the waiting build before inserting the build that replaces it.
    CONSTRAINT fk_builds_superseded_by FOREIGN KEY (superseded_by) REFERENCES build_queue.builds (build_id)
        ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_builds_dispatched_deadline
        CHECK (status <> 'DISPATCHED' OR (start_deadline IS NOT NULL AND attempt >= 1)),
    CONSTRAINT ck_builds_running_lease
        CHECK (status <> 'RUNNING' OR (started_at IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CONSTRAINT ck_builds_superseded_reason CHECK (status <> 'SUPERSEDED' OR status_reason = 'SUPERSEDED'),
    CONSTRAINT ck_builds_finished_at CHECK (finished_at IS NOT NULL OR status IN ('QUEUED', 'DISPATCHED', 'RUNNING'))
);

-- One build of an app in flight at a time. The dispatcher relies on it rather than enforcing it.
CREATE UNIQUE INDEX ux_builds_app_in_flight ON build_queue.builds (app_id)
    WHERE status IN ('DISPATCHED', 'RUNNING');

-- One waiting build per app and branch: coalescing is a constraint.
CREATE UNIQUE INDEX ux_builds_app_branch_queued ON build_queue.builds (app_id, branch)
    WHERE status = 'QUEUED';

CREATE INDEX ix_builds_queued ON build_queue.builds (org_id, priority_class, enqueued_at)
    WHERE status = 'QUEUED';
CREATE INDEX ix_builds_in_flight ON build_queue.builds (org_id)
    WHERE status IN ('DISPATCHED', 'RUNNING');
CREATE INDEX ix_builds_start_deadline ON build_queue.builds (start_deadline)
    WHERE status = 'DISPATCHED';
CREATE INDEX ix_builds_lease ON build_queue.builds (lease_expires_at)
    WHERE status = 'RUNNING';
CREATE INDEX ix_builds_queue_age ON build_queue.builds (enqueued_at)
    WHERE status = 'QUEUED';
CREATE INDEX ix_builds_app_history ON build_queue.builds (org_id, app_id, enqueued_at DESC);
CREATE INDEX ix_builds_finished ON build_queue.builds (finished_at)
    WHERE finished_at IS NOT NULL;
CREATE INDEX ix_builds_superseded_by ON build_queue.builds (superseded_by)
    WHERE superseded_by IS NOT NULL;

-- One row: when the lease sweeper last found the backbone unreachable, null while it's reachable.
CREATE TABLE build_queue.lease_sweeper_state (
    id                 SMALLINT     PRIMARY KEY CONSTRAINT ck_lease_sweeper_state_singleton CHECK (id = 1),
    unreachable_since  TIMESTAMPTZ
);
INSERT INTO build_queue.lease_sweeper_state (id) VALUES (1);

-- outbox_events and processed_events: platform-common-outbox's reference-schema.sql, qualified to
-- build_queue and otherwise unchanged (ADR-0020). OutboxSchemaAssertions checks they still match.
CREATE TABLE build_queue.outbox_events (
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
CREATE INDEX ix_outbox_pending ON build_queue.outbox_events (tx_id, id) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_org_open ON build_queue.outbox_events (org_id, tx_id, id) WHERE status <> 'PUBLISHED';
CREATE INDEX ix_outbox_published_at ON build_queue.outbox_events (published_at) WHERE status = 'PUBLISHED';

CREATE TABLE build_queue.processed_events (
    event_id      UUID         NOT NULL,
    consumer      VARCHAR(64)  NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
CREATE INDEX ix_processed_events_at ON build_queue.processed_events (processed_at);
