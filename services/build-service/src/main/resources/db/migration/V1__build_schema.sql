CREATE SCHEMA IF NOT EXISTS build;

CREATE TABLE build.apps (
    app_id      TEXT         CONSTRAINT pk_apps PRIMARY KEY,
    org_id      TEXT         NOT NULL,
    status      TEXT         NOT NULL CONSTRAINT ck_apps_status CHECK (status IN ('ACTIVE', 'DELETED')),
    deleted_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_apps_org_app UNIQUE (org_id, app_id)
);

CREATE TABLE build.orgs (
    org_id      TEXT         CONSTRAINT pk_orgs PRIMARY KEY,
    status      TEXT         NOT NULL CONSTRAINT ck_orgs_status CHECK (status IN ('ACTIVE', 'DELETED')),
    deleted_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE build.org_memberships (
    org_id          TEXT         NOT NULL,
    user_id         TEXT         NOT NULL,
    role            TEXT         NOT NULL CONSTRAINT ck_org_memberships_role
                                     CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER')),
    status          TEXT         NOT NULL CONSTRAINT ck_org_memberships_status CHECK (status IN ('ACTIVE', 'REMOVED')),
    source_version  BIGINT       NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_org_memberships PRIMARY KEY (org_id, user_id)
);

CREATE TABLE build.build_settings (
    org_id           TEXT         NOT NULL,
    app_id           TEXT         NOT NULL,
    strategy         TEXT         NOT NULL DEFAULT 'AUTO' CONSTRAINT ck_build_settings_strategy
                                      CHECK (strategy IN ('AUTO', 'DOCKERFILE', 'BUILDPACKS')),
    dockerfile_path  TEXT         NOT NULL DEFAULT 'Dockerfile' CONSTRAINT ck_build_settings_dockerfile_path
                                      CHECK (length(dockerfile_path) BETWEEN 1 AND 255
                                             AND dockerfile_path !~ '(^/|(^|/)\.\.(/|$))'),
    version          BIGINT       NOT NULL DEFAULT 0,
    updated_by       TEXT,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_build_settings PRIMARY KEY (app_id),
    CONSTRAINT fk_build_settings_app FOREIGN KEY (org_id, app_id)
        REFERENCES build.apps (org_id, app_id) ON DELETE CASCADE
);

CREATE TABLE build.builds (
    build_id              TEXT         CONSTRAINT pk_builds PRIMARY KEY,
    org_id                TEXT         NOT NULL,
    app_id                TEXT         NOT NULL,
    branch                TEXT         NOT NULL,
    commit_sha            TEXT         NOT NULL CONSTRAINT ck_builds_commit_sha CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    push_event_id         UUID         NOT NULL,
    trigger               TEXT         NOT NULL,
    attempt               INT          NOT NULL CONSTRAINT ck_builds_attempt CHECK (attempt >= 1),
    provider              TEXT         NOT NULL,
    installation_id       BIGINT       NOT NULL,
    repo_id               BIGINT       NOT NULL,
    repo                  TEXT         NOT NULL,
    root_directory        TEXT         NOT NULL CONSTRAINT ck_builds_root_directory
                                           CHECK (root_directory !~ '(^/|(^|/)\.\.(/|$))'),
    start_by              TIMESTAMPTZ  NOT NULL,
    max_duration_seconds  BIGINT       NOT NULL CONSTRAINT ck_builds_max_duration CHECK (max_duration_seconds > 0),

    strategy_setting      TEXT         NOT NULL CONSTRAINT ck_builds_strategy_setting
                                           CHECK (strategy_setting IN ('AUTO', 'DOCKERFILE', 'BUILDPACKS')),
    dockerfile_path       TEXT         NOT NULL CONSTRAINT ck_builds_dockerfile_path
                                           CHECK (length(dockerfile_path) BETWEEN 1 AND 255
                                                  AND dockerfile_path !~ '(^/|(^|/)\.\.(/|$))'),
    strategy              TEXT         CONSTRAINT ck_builds_strategy CHECK (strategy IN ('DOCKERFILE', 'BUILDPACKS')),

    status                TEXT         NOT NULL CONSTRAINT ck_builds_status
                                           CHECK (status IN ('ACCEPTED', 'DROPPED', 'STARTING', 'RUNNING', 'SUCCEEDED',
                                                             'FAILED')),
    failure_code          TEXT         CONSTRAINT ck_builds_failure_code
                                           CHECK (failure_code IN ('SOURCE_UNAVAILABLE', 'SOURCE_NOT_FOUND',
                                                                   'DETECTION_FAILED', 'BUILD_FAILED', 'RESOURCE_LIMIT',
                                                                   'PUSH_FAILED', 'TIMEOUT', 'LAUNCH_FAILED',
                                                                   'INFRASTRUCTURE', 'APP_DELETED', 'ORG_DELETED')),
    failure_reason        TEXT         CONSTRAINT ck_builds_failure_reason CHECK (length(failure_reason) <= 512),
    drop_count            INT          NOT NULL DEFAULT 0,

    job_name              TEXT         NOT NULL,
    launch_attempts       INT          NOT NULL DEFAULT 0,
    launch_last_error     TEXT         CONSTRAINT ck_builds_launch_last_error
                                           CHECK (launch_last_error IN ('SOURCE_UNAVAILABLE', 'LAUNCH_FAILED')),
    next_launch_at        TIMESTAMPTZ,
    launch_claimed_until  TIMESTAMPTZ,
    job_created_at        TIMESTAMPTZ,
    secret_deleted_at     TIMESTAMPTZ,
    cleaned_up_at         TIMESTAMPTZ,

    image_repository      TEXT,
    image_tag             TEXT,
    image_digest          TEXT         CONSTRAINT ck_builds_image_digest CHECK (image_digest ~ '^sha256:[0-9a-f]{64}$'),

    log_bytes             BIGINT       NOT NULL DEFAULT 0,
    log_truncated         BOOLEAN      NOT NULL DEFAULT false,
    log_complete          BOOLEAN      NOT NULL DEFAULT false,
    log_owner             TEXT,
    log_lease_until       TIMESTAMPTZ,

    traceparent           TEXT,
    accepted_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at            TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_builds_app FOREIGN KEY (org_id, app_id) REFERENCES build.apps (org_id, app_id),
    -- Only a build failed by teardown straight from intake was never started; anything else without
    -- started_at would be a build that ran without build.started being published first.
    CONSTRAINT ck_builds_started CHECK (status IN ('ACCEPTED', 'DROPPED') OR started_at IS NOT NULL
                                        OR (status = 'FAILED' AND failure_code IN ('APP_DELETED', 'ORG_DELETED'))),
    CONSTRAINT ck_builds_terminal CHECK ((status IN ('SUCCEEDED', 'FAILED')) = (finished_at IS NOT NULL)),
    CONSTRAINT ck_builds_success CHECK (status <> 'SUCCEEDED'
                                        OR (image_digest IS NOT NULL AND image_tag IS NOT NULL AND strategy IS NOT NULL)),
    CONSTRAINT ck_builds_failure CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CONSTRAINT ck_builds_running CHECK (status <> 'RUNNING' OR job_created_at IS NOT NULL)
);

CREATE INDEX ix_builds_accepted ON build.builds (accepted_at) WHERE status = 'ACCEPTED';
CREATE INDEX ix_builds_in_flight ON build.builds (status, started_at) WHERE status IN ('STARTING', 'RUNNING');
CREATE INDEX ix_builds_launchable ON build.builds (next_launch_at) WHERE status = 'STARTING' AND job_created_at IS NULL;
CREATE INDEX ix_builds_cleanup ON build.builds (finished_at) WHERE finished_at IS NOT NULL AND cleaned_up_at IS NULL;
CREATE INDEX ix_builds_app ON build.builds (org_id, app_id, accepted_at DESC);
CREATE INDEX ix_builds_retention ON build.builds (finished_at) WHERE finished_at IS NOT NULL;

CREATE TABLE build.build_steps (
    build_id     TEXT         NOT NULL,
    step         TEXT         NOT NULL CONSTRAINT ck_build_steps_step CHECK (step IN ('FETCH', 'BUILD', 'PUSH')),
    status       TEXT         NOT NULL CONSTRAINT ck_build_steps_status
                                  CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    exit_code    INT,
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    CONSTRAINT pk_build_steps PRIMARY KEY (build_id, step),
    CONSTRAINT fk_build_steps_build FOREIGN KEY (build_id) REFERENCES build.builds (build_id) ON DELETE CASCADE
);

CREATE TABLE build.build_log_chunks (
    build_id    TEXT         NOT NULL,
    seq         BIGINT       NOT NULL CONSTRAINT ck_build_log_chunks_seq CHECK (seq >= 0),
    org_id      TEXT         NOT NULL,
    step        TEXT         NOT NULL CONSTRAINT ck_build_log_chunks_step
                                 CHECK (step IN ('FETCH', 'BUILD', 'PUSH', 'SYSTEM')),
    content     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_build_log_chunks PRIMARY KEY (build_id, seq),
    CONSTRAINT fk_build_log_chunks_build FOREIGN KEY (build_id) REFERENCES build.builds (build_id) ON DELETE CASCADE
);

-- One row: when this service last lost sight of the Kubernetes API, and when it last got it back.
CREATE TABLE build.observer_state (
    id                 BOOLEAN      DEFAULT true CONSTRAINT pk_observer_state PRIMARY KEY
                                    CONSTRAINT ck_observer_state_singleton CHECK (id),
    unreachable_since  TIMESTAMPTZ,
    recovered_at       TIMESTAMPTZ
);
INSERT INTO build.observer_state (id) VALUES (true);

-- outbox_events and processed_events: platform-common-outbox's reference schema, qualified to build and
-- otherwise unchanged. OutboxSchemaAssertions checks they still match.
CREATE TABLE build.outbox_events (
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
CREATE INDEX ix_outbox_pending ON build.outbox_events (tx_id, id) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_org_open ON build.outbox_events (org_id, tx_id, id) WHERE status <> 'PUBLISHED';
CREATE INDEX ix_outbox_published_at ON build.outbox_events (published_at) WHERE status = 'PUBLISHED';

CREATE TABLE build.processed_events (
    event_id      UUID         NOT NULL,
    consumer      VARCHAR(64)  NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
CREATE INDEX ix_processed_events_at ON build.processed_events (processed_at);
