CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.organizations (
    org_id        VARCHAR(64)  PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    slug          VARCHAR(64)  NOT NULL,
    owner_user_id VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_organizations_slug ON identity.organizations (slug);

CREATE TABLE identity.users (
    id               UUID         PRIMARY KEY,
    org_id           VARCHAR(64)  NOT NULL,
    keycloak_user_id VARCHAR(64)  NOT NULL,
    email            VARCHAR(255) NOT NULL,
    display_name     VARCHAR(255) NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_users_keycloak_user_id ON identity.users (keycloak_user_id);
CREATE UNIQUE INDEX ux_users_email ON identity.users (email);
CREATE INDEX ix_users_org_id ON identity.users (org_id);

CREATE TABLE identity.idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status INT          NOT NULL,
    response_body   JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE identity.one_time_action_tokens (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES identity.users (id),
    purpose    VARCHAR(32)  NOT NULL,
    token_hash VARCHAR(64)  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_one_time_action_tokens_hash ON identity.one_time_action_tokens (token_hash);
CREATE INDEX ix_one_time_action_tokens_expires_at ON identity.one_time_action_tokens (expires_at);

CREATE TABLE identity.consumed_invite_tokens (
    jti         VARCHAR(64) PRIMARY KEY,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_consumed_invite_tokens_consumed_at ON identity.consumed_invite_tokens (consumed_at);
