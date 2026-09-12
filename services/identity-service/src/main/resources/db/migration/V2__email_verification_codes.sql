CREATE TABLE identity.email_verification_codes (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES identity.users (id),
    code_hash   VARCHAR(64)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_email_verification_codes_user_id ON identity.email_verification_codes (user_id);
CREATE INDEX ix_email_verification_codes_expires_at ON identity.email_verification_codes (expires_at);
