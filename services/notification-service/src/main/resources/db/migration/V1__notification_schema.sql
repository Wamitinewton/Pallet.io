CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE notification.notifications (
    id                UUID PRIMARY KEY,
    org_id            VARCHAR(64)  NOT NULL,
    notification_type VARCHAR(128) NOT NULL,
    source_event_id   UUID         NOT NULL,
    dedupe_key        VARCHAR(255),
    audience          VARCHAR(16)  NOT NULL,
    rendered_title    VARCHAR(255) NOT NULL,
    rendered_body     TEXT         NOT NULL,
    variables         JSONB        NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_notifications_source_event_id ON notification.notifications (source_event_id);
CREATE UNIQUE INDEX ux_notifications_org_dedupe ON notification.notifications (org_id, dedupe_key)
    WHERE dedupe_key IS NOT NULL;

CREATE TABLE notification.notification_deliveries (
    id              UUID PRIMARY KEY,
    notification_id UUID        NOT NULL REFERENCES notification.notifications (id),
    channel         VARCHAR(16) NOT NULL,
    recipient       VARCHAR(255) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    attempt_count   INT         NOT NULL DEFAULT 0,
    last_error      TEXT,
    sent_at         TIMESTAMPTZ,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_deliveries_notification_channel_recipient
    ON notification.notification_deliveries (notification_id, channel, recipient);
CREATE INDEX ix_deliveries_recipient_channel_created
    ON notification.notification_deliveries (recipient, channel, created_at DESC)
    WHERE channel = 'IN_APP';
CREATE INDEX ix_deliveries_unread
    ON notification.notification_deliveries (recipient, channel)
    WHERE channel = 'IN_APP' AND read_at IS NULL;
CREATE INDEX ix_deliveries_throttled
    ON notification.notification_deliveries (status, created_at)
    WHERE status = 'THROTTLED';

CREATE TABLE notification.org_members (
    org_id     VARCHAR(64)  NOT NULL,
    user_id    VARCHAR(64)  NOT NULL,
    email      VARCHAR(255) NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, user_id)
);

CREATE INDEX ix_org_members_org_status ON notification.org_members (org_id, status);
