CREATE TABLE stockrescue.user_account (
    id UUID PRIMARY KEY,
    email_display VARCHAR(254) NOT NULL,
    email_normalized VARCHAR(254) GENERATED ALWAYS AS (lower(btrim(email_display) COLLATE "C")) STORED,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_email_valid CHECK (
        length(email_normalized) BETWEEN 3 AND 254
        AND email_normalized !~ '[^!-~]'
        AND email_normalized ~ '^[^@]+@[^@]+$'
    ),
    UNIQUE (email_normalized),
    UNIQUE (id, email_normalized)
);

CREATE TABLE stockrescue.credential (
    user_id UUID PRIMARY KEY REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    password_hash VARCHAR(1024) NOT NULL CHECK (length(password_hash) BETWEEN 20 AND 1024),
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    failed_attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempt_count >= 0),
    failure_window_started_at TIMESTAMPTZ,
    locked_until TIMESTAMPTZ
);

CREATE TABLE stockrescue.organization (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL CHECK (length(btrim(name)) > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_by_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (updated_at >= created_at),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE TABLE stockrescue.membership (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    org_role VARCHAR(16) NOT NULL CHECK (org_role IN ('ORG_ADMIN', 'MEMBER')),
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'REVOKED')),
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((state = 'REVOKED') = (revoked_at IS NOT NULL)),
    UNIQUE (org_id, user_id),
    UNIQUE (org_id, id)
);
CREATE INDEX membership_user_org_idx ON stockrescue.membership(user_id, org_id);

CREATE TABLE stockrescue.audit_entry (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    org_id UUID REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    resource_type VARCHAR(64) NOT NULL CHECK (length(btrim(resource_type)) > 0),
    resource_id UUID NOT NULL,
    action VARCHAR(64) NOT NULL CHECK (length(btrim(action)) > 0),
    changes JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(changes) = 'object'),
    reason VARCHAR(500),
    correlation_id VARCHAR(64) NOT NULL CHECK (correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX audit_entry_org_time_idx ON stockrescue.audit_entry(org_id, occurred_at, id);
CREATE INDEX audit_entry_actor_time_idx ON stockrescue.audit_entry(actor_user_id, occurred_at);

CREATE FUNCTION stockrescue.reject_history_mutation() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    RAISE EXCEPTION 'History rows are immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER audit_entry_immutable BEFORE UPDATE OR DELETE ON stockrescue.audit_entry
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER audit_entry_no_truncate BEFORE TRUNCATE ON stockrescue.audit_entry
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
