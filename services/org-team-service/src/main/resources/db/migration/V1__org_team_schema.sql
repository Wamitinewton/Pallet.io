CREATE SCHEMA IF NOT EXISTS org_team;

CREATE TABLE org_team.organizations (
    org_id         VARCHAR(64)  PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    slug           VARCHAR(64)  NOT NULL,
    owner_user_id  VARCHAR(64)  NOT NULL,
    status         VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'DELETED')),
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ,
    deleted_by     VARCHAR(64),
    purged_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_organizations_slug ON org_team.organizations (slug);
CREATE INDEX ix_organizations_deleted_at ON org_team.organizations (deleted_at)
    WHERE status = 'DELETED' AND purged_at IS NULL;

CREATE TABLE org_team.memberships (
    org_id            VARCHAR(64)  NOT NULL REFERENCES org_team.organizations (org_id),
    user_id           VARCHAR(64)  NOT NULL,
    email             VARCHAR(255) NOT NULL,
    display_name      VARCHAR(255) NOT NULL,
    role              VARCHAR(16)  NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER')),
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'REMOVED')),
    version           BIGINT       NOT NULL DEFAULT 0,
    joined_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    removed_at        TIMESTAMPTZ,
    removed_by        VARCHAR(64),
    profile_synced_at TIMESTAMPTZ,
    PRIMARY KEY (org_id, user_id)
);
CREATE UNIQUE INDEX ux_memberships_one_active_owner ON org_team.memberships (org_id)
    WHERE role = 'OWNER' AND status = 'ACTIVE';
CREATE UNIQUE INDEX ux_memberships_active_email ON org_team.memberships (org_id, lower(email))
    WHERE status = 'ACTIVE';
CREATE INDEX ix_memberships_org_status ON org_team.memberships (org_id, status);
CREATE INDEX ix_memberships_removed_at ON org_team.memberships (removed_at) WHERE status = 'REMOVED';

CREATE TABLE org_team.invites (
    id                  UUID         PRIMARY KEY,
    org_id              VARCHAR(64)  NOT NULL REFERENCES org_team.organizations (org_id),
    email               VARCHAR(255) NOT NULL,
    role                VARCHAR(16)  NOT NULL CHECK (role IN ('ADMIN', 'DEVELOPER', 'VIEWER')),
    invited_by_user_id  VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    send_count          INT          NOT NULL DEFAULT 1,
    last_sent_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ  NOT NULL,
    responded_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_invites_pending_email ON org_team.invites (org_id, lower(email))
    WHERE status = 'PENDING';
CREATE INDEX ix_invites_pending_expiry ON org_team.invites (expires_at) WHERE status = 'PENDING';
CREATE INDEX ix_invites_org_status_created ON org_team.invites (org_id, status, created_at DESC);
CREATE INDEX ix_invites_terminal ON org_team.invites (responded_at) WHERE status <> 'PENDING';

CREATE TABLE org_team.teams (
    id          UUID         PRIMARY KEY,
    org_id      VARCHAR(64)  NOT NULL REFERENCES org_team.organizations (org_id),
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(63)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (org_id, id),
    UNIQUE (org_id, slug)
);

CREATE TABLE org_team.team_members (
    team_id   UUID         NOT NULL,
    user_id   VARCHAR(64)  NOT NULL,
    org_id    VARCHAR(64)  NOT NULL,
    added_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    added_by  VARCHAR(64)  NOT NULL,
    PRIMARY KEY (team_id, user_id),
    FOREIGN KEY (org_id, team_id) REFERENCES org_team.teams (org_id, id) ON DELETE CASCADE,
    FOREIGN KEY (org_id, user_id) REFERENCES org_team.memberships (org_id, user_id)
);
CREATE INDEX ix_team_members_user ON org_team.team_members (org_id, user_id);

CREATE TABLE org_team.apps (
    id              UUID         PRIMARY KEY,
    org_id          VARCHAR(64)  NOT NULL REFERENCES org_team.organizations (org_id),
    team_id         UUID,
    name            VARCHAR(100) NOT NULL,
    slug            VARCHAR(63)  NOT NULL,
    cloud_provider  VARCHAR(16)  NOT NULL CHECK (cloud_provider IN ('AWS', 'GCP')),
    region          VARCHAR(32)  NOT NULL,
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'DELETED')),
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    FOREIGN KEY (org_id, team_id) REFERENCES org_team.teams (org_id, id) ON DELETE SET NULL (team_id)
);
CREATE UNIQUE INDEX ux_apps_active_slug ON org_team.apps (org_id, slug) WHERE status = 'ACTIVE';
CREATE INDEX ix_apps_org_status ON org_team.apps (org_id, status);
CREATE INDEX ix_apps_team ON org_team.apps (org_id, team_id);

CREATE FUNCTION org_team.forbid_app_placement_change() RETURNS trigger AS $$
BEGIN
    IF NEW.cloud_provider IS DISTINCT FROM OLD.cloud_provider OR NEW.region IS DISTINCT FROM OLD.region THEN
        RAISE EXCEPTION 'apps.cloud_provider and apps.region are immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_apps_placement_immutable BEFORE UPDATE ON org_team.apps
    FOR EACH ROW EXECUTE FUNCTION org_team.forbid_app_placement_change();

CREATE TABLE org_team.outbox_events (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id         UUID         NOT NULL UNIQUE,
    org_id           VARCHAR(64)  NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    payload          JSONB,
    sensitive        BOOLEAN      NOT NULL DEFAULT false,
    traceparent      VARCHAR(64),
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'PARKED')),
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error       VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ
);
CREATE INDEX ix_outbox_pending ON org_team.outbox_events (id) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_org_open ON org_team.outbox_events (org_id, id) WHERE status <> 'PUBLISHED';
CREATE INDEX ix_outbox_published_at ON org_team.outbox_events (published_at) WHERE status = 'PUBLISHED';

CREATE TABLE org_team.processed_events (
    event_id      UUID         NOT NULL,
    consumer      VARCHAR(64)  NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
CREATE INDEX ix_processed_events_at ON org_team.processed_events (processed_at);
