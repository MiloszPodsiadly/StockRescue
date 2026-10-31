CREATE TABLE stockrescue.store (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    code VARCHAR(32) NOT NULL,
    code_normalized VARCHAR(32) GENERATED ALWAYS AS (upper(btrim(code) COLLATE "C")) STORED,
    name VARCHAR(160) NOT NULL CHECK (length(btrim(name)) > 0),
    address_line1 VARCHAR(200),
    city VARCHAR(100),
    postal_code VARCHAR(20),
    country_code VARCHAR(2) CHECK (country_code ~ '^[A-Z]{2}$'),
    zone_id VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (updated_at >= created_at),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (code_normalized ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    UNIQUE (org_id, code_normalized),
    UNIQUE (org_id, id)
);

CREATE FUNCTION stockrescue.validate_store_zone() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_timezone_names
        WHERE name = NEW.zone_id
          AND (name = 'UTC' OR name LIKE '%/%')
          AND name NOT LIKE 'posix/%' AND name NOT LIKE 'right/%'
    ) THEN
        RAISE EXCEPTION 'Store requires an IANA timezone identifier'
            USING ERRCODE = '23514', CONSTRAINT = 'store_zone_id_valid';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER store_zone_valid BEFORE INSERT OR UPDATE OF zone_id ON stockrescue.store
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_store_zone();
COMMENT ON COLUMN stockrescue.store.zone_id IS 'IANA timezone (or UTC); business dates never use the database/server timezone implicitly.';

CREATE TABLE stockrescue.storage_zone (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL CHECK (length(btrim(name)) > 0),
    temperature_class VARCHAR(16) NOT NULL CHECK (temperature_class IN ('AMBIENT', 'CHILLED', 'FROZEN')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (org_id, store_id, name),
    UNIQUE (org_id, id),
    FOREIGN KEY (org_id, store_id) REFERENCES stockrescue.store(org_id, id) ON DELETE RESTRICT
);
COMMENT ON TABLE stockrescue.storage_zone IS 'Configured compatibility only; does not assert physical availability or stock capacity.';

CREATE TABLE stockrescue.store_grant (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    store_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL CHECK (role IN ('STORE_MANAGER', 'INVENTORY_OPERATOR', 'READ_ONLY_ANALYST')),
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'REVOKED')),
    revoked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((state = 'REVOKED') = (revoked_at IS NOT NULL)),
    UNIQUE (org_id, membership_id, store_id),
    UNIQUE (org_id, id),
    FOREIGN KEY (org_id, membership_id) REFERENCES stockrescue.membership(org_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (org_id, store_id) REFERENCES stockrescue.store(org_id, id) ON DELETE RESTRICT
);
CREATE INDEX store_grant_store_idx ON stockrescue.store_grant(org_id, store_id);

CREATE TABLE stockrescue.organization_invitation (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    email_normalized VARCHAR(254) NOT NULL CHECK (
        email_normalized = lower(btrim(email_normalized) COLLATE "C")
        AND email_normalized !~ '[^!-~]' AND email_normalized ~ '^[^@]+@[^@]+$'
    ),
    token_digest BYTEA NOT NULL UNIQUE CHECK (octet_length(token_digest) = 32),
    invited_by_membership_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    accepted_by_user_id UUID,
    accepted_at TIMESTAMPTZ,
    UNIQUE (org_id, id),
    CHECK ((status = 'ACCEPTED') = (accepted_by_user_id IS NOT NULL AND accepted_at IS NOT NULL)),
    CHECK (status = 'ACCEPTED' OR (accepted_by_user_id IS NULL AND accepted_at IS NULL)),
    CHECK (accepted_at IS NULL OR (accepted_at >= created_at AND accepted_at < expires_at)),
    FOREIGN KEY (org_id, invited_by_membership_id) REFERENCES stockrescue.membership(org_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (accepted_by_user_id, email_normalized) REFERENCES stockrescue.user_account(id, email_normalized) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX invitation_pending_email_idx ON stockrescue.organization_invitation(org_id, email_normalized) WHERE status = 'PENDING';
CREATE INDEX invitation_expiry_idx ON stockrescue.organization_invitation(expires_at) WHERE status = 'PENDING';

CREATE FUNCTION stockrescue.validate_invitation_transition() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF OLD.status <> 'PENDING' OR
        (NEW.id, NEW.org_id, NEW.email_normalized, NEW.token_digest, NEW.invited_by_membership_id, NEW.created_at, NEW.expires_at)
        IS DISTINCT FROM
        (OLD.id, OLD.org_id, OLD.email_normalized, OLD.token_digest, OLD.invited_by_membership_id, OLD.created_at, OLD.expires_at) THEN
        RAISE EXCEPTION 'Invitation identity and terminal state are immutable' USING ERRCODE = '55000';
    END IF;
    IF NEW.status = 'ACCEPTED' AND clock_timestamp() >= OLD.expires_at THEN
        RAISE EXCEPTION 'Invitation has expired' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER invitation_transition BEFORE UPDATE ON stockrescue.organization_invitation
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_invitation_transition();
CREATE TRIGGER invitation_no_delete BEFORE DELETE ON stockrescue.organization_invitation
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER invitation_no_truncate BEFORE TRUNCATE ON stockrescue.organization_invitation
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE TABLE stockrescue.invitation_store_grant (
    org_id UUID NOT NULL,
    invitation_id UUID NOT NULL,
    store_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL CHECK (role IN ('STORE_MANAGER', 'INVENTORY_OPERATOR', 'READ_ONLY_ANALYST')),
    PRIMARY KEY (invitation_id, store_id),
    FOREIGN KEY (org_id, invitation_id) REFERENCES stockrescue.organization_invitation(org_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (org_id, store_id) REFERENCES stockrescue.store(org_id, id) ON DELETE RESTRICT
);
CREATE FUNCTION stockrescue.validate_invitation_grant() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE invitation_status TEXT;
BEGIN
    SELECT status INTO invitation_status FROM stockrescue.organization_invitation
        WHERE org_id = NEW.org_id AND id = NEW.invitation_id FOR UPDATE;
    IF invitation_status IS NOT NULL AND invitation_status <> 'PENDING' THEN
        RAISE EXCEPTION 'Cannot add grants to a terminal invitation' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER invitation_grant_pending BEFORE INSERT ON stockrescue.invitation_store_grant
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_invitation_grant();
CREATE TRIGGER invitation_grant_immutable BEFORE UPDATE OR DELETE ON stockrescue.invitation_store_grant
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER invitation_grant_no_truncate BEFORE TRUNCATE ON stockrescue.invitation_store_grant
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE TABLE stockrescue.revocation_record (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    membership_id UUID,
    store_grant_id UUID,
    revoked_by_membership_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) > 0),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((membership_id IS NULL) <> (store_grant_id IS NULL)),
    FOREIGN KEY (org_id, membership_id) REFERENCES stockrescue.membership(org_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (org_id, store_grant_id) REFERENCES stockrescue.store_grant(org_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (org_id, revoked_by_membership_id) REFERENCES stockrescue.membership(org_id, id) ON DELETE RESTRICT
);
CREATE INDEX revocation_org_time_idx ON stockrescue.revocation_record(org_id, occurred_at);
CREATE TRIGGER revocation_immutable BEFORE UPDATE OR DELETE ON stockrescue.revocation_record
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER revocation_no_truncate BEFORE TRUNCATE ON stockrescue.revocation_record
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE TABLE stockrescue.transport_lane (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    origin_store_id UUID NOT NULL,
    destination_store_id UUID NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (origin_store_id <> destination_store_id),
    UNIQUE (org_id, origin_store_id, destination_store_id),
    UNIQUE (org_id, id),
    FOREIGN KEY (org_id, origin_store_id) REFERENCES stockrescue.store(org_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (org_id, destination_store_id) REFERENCES stockrescue.store(org_id, id) ON DELETE RESTRICT
);
CREATE INDEX transport_lane_destination_idx ON stockrescue.transport_lane(org_id, destination_store_id);

CREATE TABLE stockrescue.lane_revision (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    lane_id UUID NOT NULL,
    revision_no BIGINT NOT NULL CHECK (revision_no > 0),
    cost_amount NUMERIC NOT NULL CHECK (
        cost_amount >= 0 AND cost_amount < 1000000000000000
        AND cost_amount <> 'NaN'::numeric AND scale(cost_amount) <= 4
    ),
    currency_code VARCHAR(3) NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$' AND currency_code NOT IN ('XXX', 'XTS')),
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes > 0),
    effective_at TIMESTAMPTZ NOT NULL,
    recorded_by_membership_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (lane_id, revision_no),
    UNIQUE (lane_id, effective_at),
    FOREIGN KEY (org_id, lane_id) REFERENCES stockrescue.transport_lane(org_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (org_id, recorded_by_membership_id) REFERENCES stockrescue.membership(org_id, id) ON DELETE RESTRICT
);
COMMENT ON TABLE stockrescue.lane_revision IS 'Immutable configured cost/duration evidence. Does not prove transport capacity; ISO currency registry validation belongs to the command boundary.';

CREATE FUNCTION stockrescue.validate_lane_revision_order() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE previous_revision BIGINT; previous_effective TIMESTAMPTZ;
BEGIN
    PERFORM 1 FROM stockrescue.transport_lane WHERE org_id = NEW.org_id AND id = NEW.lane_id FOR UPDATE;
    SELECT revision_no, effective_at INTO previous_revision, previous_effective
        FROM stockrescue.lane_revision WHERE org_id = NEW.org_id AND lane_id = NEW.lane_id
        ORDER BY revision_no DESC LIMIT 1;
    IF NEW.revision_no <> coalesce(previous_revision, 0) + 1
        OR (previous_effective IS NOT NULL AND NEW.effective_at <= previous_effective) THEN
        RAISE EXCEPTION 'Lane revisions require consecutive numbers and increasing effective times' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER lane_revision_order BEFORE INSERT ON stockrescue.lane_revision
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_lane_revision_order();
CREATE TRIGGER lane_revision_immutable BEFORE UPDATE OR DELETE ON stockrescue.lane_revision
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER lane_revision_no_truncate BEFORE TRUNCATE ON stockrescue.lane_revision
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
