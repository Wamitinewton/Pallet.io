CREATE SCHEMA IF NOT EXISTS git_integration;

CREATE TABLE git_integration.installations (
    installation_id       BIGINT       PRIMARY KEY,
    account_id            BIGINT       NOT NULL,
    account_login         VARCHAR(100) NOT NULL,
    account_type          VARCHAR(16)  NOT NULL CHECK (account_type IN ('User', 'Organization')),
    repository_selection  VARCHAR(16)  NOT NULL CHECK (repository_selection IN ('all', 'selected')),
    status                VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    permissions           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    unused_since          TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    suspended_at          TIMESTAMPTZ,
    deleted_at            TIMESTAMPTZ
);
CREATE INDEX ix_installations_unused ON git_integration.installations (unused_since)
    WHERE unused_since IS NOT NULL AND status = 'ACTIVE';

CREATE TABLE git_integration.installation_links (
    installation_id           BIGINT       NOT NULL REFERENCES git_integration.installations (installation_id),
    org_id                    VARCHAR(64)  NOT NULL,
    status                    VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'UNLINKED')),
    linked_by_user_id         VARCHAR(64)  NOT NULL,
    linked_by_github_user_id  BIGINT       NOT NULL,
    linked_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    unlinked_at               TIMESTAMPTZ,
    version                   BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (installation_id, org_id)
);
CREATE INDEX ix_installation_links_org_active ON git_integration.installation_links (org_id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_installation_links_installation_active ON git_integration.installation_links (installation_id)
    WHERE status = 'ACTIVE';

CREATE TABLE git_integration.installation_repositories (
    installation_id  BIGINT        NOT NULL REFERENCES git_integration.installations (installation_id),
    repo_id          BIGINT        NOT NULL,
    full_name        VARCHAR(255)  NOT NULL,
    default_branch   VARCHAR(255)  NOT NULL,
    is_private       BOOLEAN       NOT NULL,
    archived         BOOLEAN       NOT NULL DEFAULT false,
    synced_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (installation_id, repo_id)
);
CREATE INDEX ix_installation_repositories_repo ON git_integration.installation_repositories (repo_id);

CREATE TABLE git_integration.apps (
    app_id      UUID         PRIMARY KEY,
    org_id      VARCHAR(64)  NOT NULL,
    slug        VARCHAR(63)  NOT NULL,
    status      VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'DELETED')),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_apps_org_app UNIQUE (org_id, app_id)
);

CREATE TABLE git_integration.org_memberships (
    org_id          VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL CHECK (role IN ('owner', 'admin', 'developer', 'viewer')),
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'REMOVED')),
    source_version  BIGINT       NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, user_id)
);

CREATE TABLE git_integration.deleted_orgs (
    org_id      VARCHAR(64)  PRIMARY KEY,
    deleted_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE git_integration.repo_links (
    app_id                   UUID          PRIMARY KEY,
    org_id                   VARCHAR(64)   NOT NULL,
    installation_id          BIGINT        NOT NULL,
    repo_id                  BIGINT        NOT NULL,
    repo_full_name           VARCHAR(255)  NOT NULL,
    production_branch        VARCHAR(255)  NOT NULL,
    root_directory           VARCHAR(255),
    auto_deploy              BOOLEAN       NOT NULL DEFAULT true,
    status                   VARCHAR(16)   NOT NULL CHECK (status IN ('ACTIVE', 'DISCONNECTED')),
    disconnect_reason        VARCHAR(32)   CHECK (disconnect_reason IN ('UNLINKED_BY_USER', 'APP_DELETED',
                                 'ORG_DELETED', 'INSTALLATION_UNLINKED', 'INSTALLATION_DELETED',
                                 'REPOSITORY_ACCESS_REMOVED', 'REPOSITORY_DELETED', 'VERIFIER_ACCESS_LOST')),
    verified_by_user_id      VARCHAR(64),
    verified_github_user_id  BIGINT,
    verified_github_login    VARCHAR(100),
    verified_permission      VARCHAR(16)   CHECK (verified_permission IN ('push', 'maintain', 'admin')),
    access_verified_at       TIMESTAMPTZ,
    access_checked_at        TIMESTAMPTZ,
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    disconnected_at          TIMESTAMPTZ,
    CONSTRAINT fk_repo_links_app FOREIGN KEY (org_id, app_id)
        REFERENCES git_integration.apps (org_id, app_id),
    CONSTRAINT fk_repo_links_installation_link FOREIGN KEY (installation_id, org_id)
        REFERENCES git_integration.installation_links (installation_id, org_id),
    CONSTRAINT ck_repo_links_active_verified CHECK (status <> 'ACTIVE' OR (
        verified_by_user_id IS NOT NULL AND verified_github_user_id IS NOT NULL
        AND verified_github_login IS NOT NULL AND verified_permission IS NOT NULL
        AND access_verified_at IS NOT NULL AND access_checked_at IS NOT NULL)),
    CONSTRAINT ck_repo_links_disconnected_reason CHECK ((status = 'DISCONNECTED') = (disconnect_reason IS NOT NULL)),
    CONSTRAINT ck_repo_links_root_directory CHECK (root_directory IS NULL OR (
        root_directory NOT LIKE '/%' AND root_directory NOT LIKE '%..%' AND length(root_directory) <= 255))
);
CREATE INDEX ix_repo_links_repo_active ON git_integration.repo_links (repo_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_repo_links_checked_active ON git_integration.repo_links (access_checked_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_repo_links_installation_org ON git_integration.repo_links (installation_id, org_id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_repo_links_disconnected_at ON git_integration.repo_links (disconnected_at)
    WHERE status = 'DISCONNECTED';

CREATE TABLE git_integration.branch_heads (
    app_id       UUID          NOT NULL REFERENCES git_integration.repo_links (app_id) ON DELETE CASCADE,
    branch       VARCHAR(255)  NOT NULL,
    head_sha     CHAR(40)      NOT NULL CHECK (head_sha ~ '^[0-9a-f]{40}$'),
    advanced_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    etag         VARCHAR(128),
    PRIMARY KEY (app_id, branch)
);

CREATE TABLE git_integration.webhook_deliveries (
    delivery_id      UUID          PRIMARY KEY,
    event            VARCHAR(64)   NOT NULL,
    action           VARCHAR(64),
    installation_id  BIGINT,
    payload          JSONB,
    status           VARCHAR(16)   NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'PARKED')),
    outcome_reason   VARCHAR(32),
    attempts         INT           NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_error       VARCHAR(500),
    traceparent      VARCHAR(64),
    received_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at     TIMESTAMPTZ
);
CREATE INDEX ix_webhook_deliveries_pending ON git_integration.webhook_deliveries (next_attempt_at)
    WHERE status = 'RECEIVED';
CREATE INDEX ix_webhook_deliveries_parked ON git_integration.webhook_deliveries (received_at)
    WHERE status = 'PARKED';
CREATE INDEX ix_webhook_deliveries_received_at ON git_integration.webhook_deliveries (received_at);

CREATE TABLE git_integration.authorization_states (
    nonce        UUID         PRIMARY KEY,
    purpose      VARCHAR(16)  NOT NULL CHECK (purpose IN ('INSTALL', 'AUTHORIZE')),
    user_id      VARCHAR(64)  NOT NULL,
    org_id       VARCHAR(64),
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_authorization_states_org CHECK ((purpose = 'INSTALL') = (org_id IS NOT NULL))
);
CREATE INDEX ix_authorization_states_expires ON git_integration.authorization_states (expires_at);

CREATE TABLE git_integration.check_runs (
    app_id               UUID          NOT NULL,
    commit_sha           CHAR(40)      NOT NULL,
    org_id               VARCHAR(64)   NOT NULL,
    check_run_id         BIGINT,
    desired_state        VARCHAR(16)   NOT NULL CHECK (desired_state IN ('queued', 'in_progress', 'completed')),
    desired_conclusion   VARCHAR(16),
    desired_details_url  VARCHAR(512),
    desired_summary      VARCHAR(1024),
    last_reported_state  VARCHAR(16),
    attempts             INT           NOT NULL DEFAULT 0,
    next_attempt_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (app_id, commit_sha)
);
CREATE INDEX ix_check_runs_pending ON git_integration.check_runs (next_attempt_at)
    WHERE last_reported_state IS DISTINCT FROM desired_state;

CREATE TABLE git_integration.manual_build_requests (
    app_id           UUID          NOT NULL,
    idempotency_key  VARCHAR(128)  NOT NULL,
    org_id           VARCHAR(64)   NOT NULL,
    request_hash     CHAR(64)      NOT NULL,
    event_id         UUID          NOT NULL,
    branch           VARCHAR(255)  NOT NULL,
    commit_sha       CHAR(40)      NOT NULL,
    requested_by     VARCHAR(64)   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (app_id, idempotency_key)
);
CREATE INDEX ix_manual_build_requests_app_recent ON git_integration.manual_build_requests (app_id, created_at);

CREATE TABLE git_integration.sync_cursors (
    name        VARCHAR(64)   PRIMARY KEY,
    cursor      VARCHAR(512),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- outbox_events and processed_events: platform-common-outbox's reference-schema.sql, qualified to
-- git_integration and otherwise unchanged (ADR-0020). OutboxSchemaAssertions checks they still match.
CREATE TABLE git_integration.outbox_events (
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
CREATE INDEX ix_outbox_pending ON git_integration.outbox_events (tx_id, id) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_org_open ON git_integration.outbox_events (org_id, tx_id, id) WHERE status <> 'PUBLISHED';
CREATE INDEX ix_outbox_published_at ON git_integration.outbox_events (published_at) WHERE status = 'PUBLISHED';

CREATE TABLE git_integration.processed_events (
    event_id      UUID         NOT NULL,
    consumer      VARCHAR(64)  NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
CREATE INDEX ix_processed_events_at ON git_integration.processed_events (processed_at);
