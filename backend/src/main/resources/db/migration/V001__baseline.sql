
-- Consolidated source: V001__baseline.sql
CREATE SCHEMA stockrescue;

COMMENT ON SCHEMA stockrescue IS 'Application schema managed by this consolidated baseline.';


-- Consolidated source: V002__identity_and_organizations.sql
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
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
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


-- Consolidated source: V003__stores_access_and_transport_configuration.sql
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
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
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


-- Consolidated source: V004__jdbc_sessions.sql
-- Spring Session JDBC's PostgreSQL layout; initialized only by Flyway.
CREATE TABLE stockrescue.spring_session (
    primary_id CHAR(36) PRIMARY KEY,
    session_id CHAR(36) NOT NULL UNIQUE,
    creation_time BIGINT NOT NULL CHECK (creation_time >= 0),
    last_access_time BIGINT NOT NULL CHECK (last_access_time >= creation_time),
    max_inactive_interval INTEGER NOT NULL CHECK (max_inactive_interval > 0),
    expiry_time BIGINT NOT NULL CHECK (expiry_time >= creation_time),
    principal_name VARCHAR(100)
);
CREATE INDEX spring_session_expiry_idx ON stockrescue.spring_session(expiry_time);
CREATE INDEX spring_session_principal_idx ON stockrescue.spring_session(principal_name);

CREATE TABLE stockrescue.spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL REFERENCES stockrescue.spring_session(primary_id) ON DELETE CASCADE,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);


-- Consolidated source: V005__organization_onboarding.sql
CREATE TABLE stockrescue.organization_onboarding (
    user_id UUID PRIMARY KEY REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    requested_name VARCHAR(160) NOT NULL CHECK (length(btrim(requested_name)) > 0),
    org_id UUID NOT NULL UNIQUE REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER organization_onboarding_immutable BEFORE UPDATE OR DELETE ON stockrescue.organization_onboarding
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER organization_onboarding_no_truncate BEFORE TRUNCATE ON stockrescue.organization_onboarding
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();


-- Consolidated source: V006__reviewed_staff_access.sql
ALTER TABLE stockrescue.membership DROP CONSTRAINT membership_state_check;
ALTER TABLE stockrescue.membership ADD CONSTRAINT membership_state_check CHECK (state IN ('ACTIVE','PENDING','REVOKED'));
ALTER TABLE stockrescue.membership ADD COLUMN pending_invitation_id UUID;
ALTER TABLE stockrescue.membership ADD CONSTRAINT membership_pending_invitation_fk
    FOREIGN KEY (org_id,pending_invitation_id) REFERENCES stockrescue.organization_invitation(org_id,id) ON DELETE RESTRICT;
ALTER TABLE stockrescue.membership ADD CONSTRAINT membership_pending_review_check
    CHECK ((state='PENDING') = (pending_invitation_id IS NOT NULL) AND (state<>'PENDING' OR org_role='MEMBER'));
ALTER TABLE stockrescue.organization_invitation ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0);

CREATE TABLE stockrescue.access_rate_limit (
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('INVITE','REDEEM')),
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts>0),
    PRIMARY KEY (actor_user_id,operation)
);


-- Consolidated source: V007__store_creation_requests.sql
CREATE TABLE stockrescue.store_creation_request (
    org_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    store_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (org_id,actor_user_id,request_id),
    UNIQUE (org_id,store_id),
    FOREIGN KEY (org_id,store_id) REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT
);
CREATE TRIGGER store_creation_request_immutable BEFORE UPDATE OR DELETE ON stockrescue.store_creation_request
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER store_creation_request_no_truncate BEFORE TRUNCATE ON stockrescue.store_creation_request
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();


-- Consolidated source: V008__zone_and_lane_configuration_evidence.sql
ALTER TABLE stockrescue.storage_zone
    ADD COLUMN name_normalized VARCHAR(100) GENERATED ALWAYS AS (lower(btrim(name) COLLATE "C")) STORED,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT storage_zone_name_normalized_unique UNIQUE (org_id,store_id,name_normalized),
    ADD CONSTRAINT storage_zone_store_identity UNIQUE (org_id,store_id,id),
    ADD CONSTRAINT storage_zone_updated_after_created CHECK (updated_at>=created_at);

CREATE FUNCTION stockrescue.validate_storage_zone_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.id,NEW.org_id,NEW.store_id,NEW.temperature_class,NEW.created_at)
        IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.store_id,OLD.temperature_class,OLD.created_at)
        OR (NOT OLD.active AND NEW.active) THEN
        RAISE EXCEPTION 'Zone identity and temperature class are fixed; deactivation is permanent' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER storage_zone_identity BEFORE UPDATE ON stockrescue.storage_zone
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_storage_zone_update();

ALTER TABLE stockrescue.transport_lane
    ADD COLUMN origin_zone_id UUID,
    ADD COLUMN destination_zone_id UUID,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT lane_zone_pair CHECK ((origin_zone_id IS NULL)=(destination_zone_id IS NULL)),
    ADD CONSTRAINT lane_origin_zone_fk FOREIGN KEY (org_id,origin_store_id,origin_zone_id)
        REFERENCES stockrescue.storage_zone(org_id,store_id,id) ON DELETE RESTRICT,
    ADD CONSTRAINT lane_destination_zone_fk FOREIGN KEY (org_id,destination_store_id,destination_zone_id)
        REFERENCES stockrescue.storage_zone(org_id,store_id,id) ON DELETE RESTRICT,
    ADD CONSTRAINT lane_updated_after_created CHECK (updated_at>=created_at);

CREATE FUNCTION stockrescue.validate_lane_identity() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.id,NEW.org_id,NEW.origin_store_id,NEW.destination_store_id,NEW.created_at)
        IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.origin_store_id,OLD.destination_store_id,OLD.created_at) THEN
        RAISE EXCEPTION 'Lane endpoints and identity are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transport_lane_identity BEFORE UPDATE ON stockrescue.transport_lane
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_lane_identity();

ALTER TABLE stockrescue.lane_revision
    ADD COLUMN origin_zone_id UUID,
    ADD COLUMN destination_zone_id UUID,
    ADD COLUMN temperature_class VARCHAR(16),
    ADD CONSTRAINT lane_revision_zone_evidence CHECK (
        (origin_zone_id IS NULL AND destination_zone_id IS NULL AND temperature_class IS NULL)
        OR (origin_zone_id IS NOT NULL AND destination_zone_id IS NOT NULL AND temperature_class IS NOT NULL AND temperature_class IN ('AMBIENT','CHILLED','FROZEN'))
    ),
    ADD CONSTRAINT revision_origin_zone_fk FOREIGN KEY (org_id,origin_zone_id)
        REFERENCES stockrescue.storage_zone(org_id,id) ON DELETE RESTRICT,
    ADD CONSTRAINT revision_destination_zone_fk FOREIGN KEY (org_id,destination_zone_id)
        REFERENCES stockrescue.storage_zone(org_id,id) ON DELETE RESTRICT,
    ADD CONSTRAINT lane_revision_org_lane_identity UNIQUE (org_id,lane_id,id),
    DROP CONSTRAINT lane_revision_lane_id_effective_at_key;

CREATE INDEX lane_revision_timeline_idx ON stockrescue.lane_revision(org_id,lane_id,effective_at,revision_no);

CREATE TABLE stockrescue.lane_revision_cancellation (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    lane_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    cancelled_by_membership_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    cancelled_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    correlation_id VARCHAR(64) CHECK (correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    UNIQUE (org_id,lane_id,revision_id),
    FOREIGN KEY (org_id,lane_id,revision_id)
        REFERENCES stockrescue.lane_revision(org_id,lane_id,id) ON DELETE RESTRICT,
    FOREIGN KEY (org_id,cancelled_by_membership_id)
        REFERENCES stockrescue.membership(org_id,id) ON DELETE RESTRICT
);
COMMENT ON TABLE stockrescue.lane_revision_cancellation IS 'Immutable evidence that a scheduled lane cost was cancelled before it became effective.';
CREATE INDEX lane_revision_cancellation_lane_idx ON stockrescue.lane_revision_cancellation(org_id,lane_id,cancelled_at);

CREATE FUNCTION stockrescue.validate_lane_revision_cancellation() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE scheduled_at TIMESTAMPTZ;
BEGIN
    PERFORM 1 FROM stockrescue.transport_lane
        WHERE org_id=NEW.org_id AND id=NEW.lane_id FOR UPDATE;

    SELECT effective_at INTO scheduled_at FROM stockrescue.lane_revision
        WHERE org_id=NEW.org_id AND lane_id=NEW.lane_id AND id=NEW.revision_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    NEW.cancelled_at := clock_timestamp();
    IF scheduled_at <= NEW.cancelled_at THEN
        RAISE EXCEPTION 'Only a future lane revision can be cancelled' USING ERRCODE='PZ001';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER lane_revision_cancellation_future
    BEFORE INSERT ON stockrescue.lane_revision_cancellation
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_lane_revision_cancellation();
CREATE TRIGGER lane_revision_cancellation_immutable
    BEFORE UPDATE OR DELETE ON stockrescue.lane_revision_cancellation
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER lane_revision_cancellation_no_truncate
    BEFORE TRUNCATE ON stockrescue.lane_revision_cancellation
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE OR REPLACE FUNCTION stockrescue.validate_lane_revision_order() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
    previous_revision BIGINT; previous_effective TIMESTAMPTZ;
    decision_at TIMESTAMPTZ;
    lane stockrescue.transport_lane%ROWTYPE;
BEGIN
    SELECT * INTO lane FROM stockrescue.transport_lane WHERE org_id=NEW.org_id AND id=NEW.lane_id FOR UPDATE;

    decision_at := clock_timestamp();
    IF NEW.effective_at IS NULL THEN
        NEW.effective_at := decision_at;
    ELSIF NEW.effective_at < decision_at THEN
        RAISE EXCEPTION 'Lane revision effective time is in the past' USING ERRCODE='PZ002';
    END IF;
    NEW.recorded_at := decision_at;

    SELECT max(r.revision_no), max(r.effective_at) FILTER (WHERE c.id IS NULL)
        INTO previous_revision,previous_effective
        FROM stockrescue.lane_revision r
        LEFT JOIN stockrescue.lane_revision_cancellation c
          ON c.org_id=r.org_id AND c.lane_id=r.lane_id AND c.revision_id=r.id
        WHERE r.org_id=NEW.org_id AND r.lane_id=NEW.lane_id;
    IF NEW.revision_no<>coalesce(previous_revision,0)+1
        OR (previous_effective IS NOT NULL AND NEW.effective_at<=previous_effective) THEN
        RAISE EXCEPTION 'Lane revisions require consecutive numbers and increasing active effective times' USING ERRCODE='23514';
    END IF;
    -- Older unconfigured lanes retain their unknown zone evidence. New configured
    -- lanes must snapshot the exact current, compatible active zone pair.
    IF (NEW.origin_zone_id,NEW.destination_zone_id) IS DISTINCT FROM (lane.origin_zone_id,lane.destination_zone_id) THEN
        RAISE EXCEPTION 'Revision zone evidence must match the lane' USING ERRCODE='23514';
    END IF;
    IF NEW.origin_zone_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM stockrescue.storage_zone a JOIN stockrescue.storage_zone b ON b.org_id=a.org_id
        WHERE a.org_id=NEW.org_id AND a.store_id=lane.origin_store_id AND a.id=NEW.origin_zone_id
          AND b.store_id=lane.destination_store_id AND b.id=NEW.destination_zone_id
          AND a.active AND b.active AND a.temperature_class=b.temperature_class AND a.temperature_class=NEW.temperature_class
    ) THEN
        RAISE EXCEPTION 'Revision requires compatible active zones' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;


-- Consolidated source: V009__catalog_and_lot_foundation.sql
CREATE FUNCTION stockrescue.catalog_gtin_valid(value TEXT) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE SET search_path=pg_catalog AS $$
    SELECT CASE WHEN value COLLATE "C" ~ '^([0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14})$' THEN
        (SELECT (sum(substring(value FROM idx FOR 1)::integer *
            CASE WHEN (length(value)-idx)%2=1 THEN 3 ELSE 1 END)
            + right(value,1)::integer)%10=0
         FROM generate_series(1,length(value)-1) AS digits(idx))
        ELSE FALSE END;
$$;

CREATE TABLE stockrescue.product (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    sku VARCHAR(64) NOT NULL,
    sku_normalized VARCHAR(64) COLLATE "C" GENERATED ALWAYS AS (upper(btrim(sku) COLLATE "C")) STORED,
    name VARCHAR(160) NOT NULL,
    gtin VARCHAR(14),
    base_unit VARCHAR(8) NOT NULL,
    temperature_class VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    archived_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT product_sku_format CHECK (sku_normalized ~ '^[A-Z0-9][A-Z0-9._/-]{0,63}$'),
    CONSTRAINT product_name_valid CHECK (length(btrim(name))>0 AND name COLLATE "C" !~ '[[:cntrl:]]'),
    CONSTRAINT product_gtin_valid CHECK (gtin IS NULL OR stockrescue.catalog_gtin_valid(gtin)),
    CONSTRAINT product_base_unit_supported CHECK (base_unit='EA'),
    CONSTRAINT product_temperature_class_supported CHECK (temperature_class IN ('AMBIENT','CHILLED','FROZEN')),
    CONSTRAINT product_version_valid CHECK (version>=0),
    CONSTRAINT product_lifecycle_valid CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at>=created_at
        AND ((NOT active)=(archived_at IS NOT NULL)) AND (archived_at IS NULL OR (isfinite(archived_at) AND archived_at>=created_at))),
    CONSTRAINT product_org_identity UNIQUE (org_id,id),
    CONSTRAINT product_org_sku_unique UNIQUE (org_id,sku_normalized)
);
CREATE INDEX product_gtin_lookup_idx ON stockrescue.product(org_id,gtin,id) WHERE gtin IS NOT NULL;
COMMENT ON COLUMN stockrescue.product.temperature_class IS 'Declared storage compatibility using the existing zone classes; not proof of continuous cold-chain safety.';

CREATE TABLE stockrescue.manufacturer_lot (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    product_id UUID NOT NULL,
    lot_code VARCHAR(128) COLLATE "C" NOT NULL,
    identity_state VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE',
    manufacturer_identity_key VARCHAR(200) COLLATE "C",
    manufacturer_source VARCHAR(256),
    manufacturer_reference VARCHAR(256),
    confirmed_by_membership_id UUID,
    confirmed_at TIMESTAMPTZ,
    labeled_value VARCHAR(256),
    date_marking_type VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    date_precision VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN',
    marking_source VARCHAR(256),
    marking_reference VARCHAR(256),
    normalized_cutoff DATE,
    normalization_policy_version_id UUID,
    normalization_policy_version INTEGER,
    normalization_version VARCHAR(64),
    recall_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    damage_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    status_source VARCHAR(256),
    status_reference VARCHAR(256),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    archived_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT lot_org_identity UNIQUE (org_id,id),
    CONSTRAINT lot_product_fk FOREIGN KEY (org_id,product_id) REFERENCES stockrescue.product(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT lot_confirmation_actor_fk FOREIGN KEY (org_id,confirmed_by_membership_id)
        REFERENCES stockrescue.membership(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT lot_code_valid CHECK (length(lot_code)>0 AND lot_code=btrim(lot_code) AND lot_code !~ '[[:cntrl:]]'),
    CONSTRAINT lot_manufacturer_key_valid CHECK (manufacturer_identity_key IS NULL OR
        (length(manufacturer_identity_key)>0 AND manufacturer_identity_key=btrim(manufacturer_identity_key)
            AND manufacturer_identity_key !~ '[[:cntrl:]]')),
    CONSTRAINT lot_manufacturer_evidence_valid CHECK (
        (manufacturer_source IS NULL OR (length(btrim(manufacturer_source))>0 AND manufacturer_source COLLATE "C" !~ '[[:cntrl:]]'))
        AND (manufacturer_reference IS NULL OR (manufacturer_source IS NOT NULL
            AND length(btrim(manufacturer_reference))>0 AND manufacturer_reference COLLATE "C" !~ '[[:cntrl:]]'))),
    CONSTRAINT lot_identity_evidence_valid CHECK (
        (identity_state='CANDIDATE' AND confirmed_by_membership_id IS NULL AND confirmed_at IS NULL)
        OR (identity_state='CONFIRMED' AND manufacturer_identity_key IS NOT NULL AND manufacturer_source IS NOT NULL
            AND manufacturer_reference IS NOT NULL AND confirmed_by_membership_id IS NOT NULL
            AND confirmed_at IS NOT NULL AND isfinite(confirmed_at))),
    CONSTRAINT lot_date_type_valid CHECK (date_marking_type IN ('USE_BY','BEST_BEFORE','BEST_BEFORE_END','UNKNOWN')),
    CONSTRAINT lot_date_precision_valid CHECK (date_precision IN ('DAY','MONTH','YEAR','UNKNOWN')),
    CONSTRAINT lot_label_valid CHECK (labeled_value IS NULL OR labeled_value COLLATE "C" !~ '[[:cntrl:]]'),
    CONSTRAINT lot_marking_evidence_valid CHECK (
        (marking_source IS NULL OR (length(btrim(marking_source))>0 AND marking_source COLLATE "C" !~ '[[:cntrl:]]'))
        AND (marking_reference IS NULL OR (marking_source IS NOT NULL
            AND length(btrim(marking_reference))>0 AND marking_reference COLLATE "C" !~ '[[:cntrl:]]'))),
    CONSTRAINT lot_normalization_evidence_valid CHECK (
        (normalized_cutoff IS NULL AND normalization_policy_version_id IS NULL
            AND normalization_policy_version IS NULL AND normalization_version IS NULL)
        OR (normalized_cutoff IS NOT NULL AND isfinite(normalized_cutoff) AND normalization_policy_version_id IS NOT NULL
            AND normalization_policy_version IS NOT NULL AND normalization_policy_version>0
            AND normalization_version IS NOT NULL AND length(btrim(normalization_version))>0
            AND normalization_version COLLATE "C" !~ '[[:cntrl:]]'
            AND labeled_value IS NOT NULL AND length(btrim(labeled_value))>0 AND marking_source IS NOT NULL
            AND date_marking_type<>'UNKNOWN' AND date_precision<>'UNKNOWN'
            AND (date_marking_type<>'USE_BY' OR date_precision='DAY'))),
    CONSTRAINT lot_cutoff_requires_policy_registry CHECK (normalized_cutoff IS NULL),
    CONSTRAINT lot_status_valid CHECK (recall_state IN ('UNKNOWN','NOT_REPORTED','RECALLED')
        AND damage_state IN ('UNKNOWN','NOT_REPORTED','DAMAGED')),
    CONSTRAINT lot_status_evidence_valid CHECK (
        (status_source IS NULL OR (length(btrim(status_source))>0 AND status_source COLLATE "C" !~ '[[:cntrl:]]'))
        AND (status_reference IS NULL OR (status_source IS NOT NULL
            AND length(btrim(status_reference))>0 AND status_reference COLLATE "C" !~ '[[:cntrl:]]'))
        AND ((recall_state='UNKNOWN' AND damage_state='UNKNOWN') OR status_source IS NOT NULL)),
    CONSTRAINT lot_version_valid CHECK (version>=0),
    CONSTRAINT lot_lifecycle_valid CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at>=created_at
        AND ((NOT active)=(archived_at IS NOT NULL)) AND (archived_at IS NULL OR (isfinite(archived_at) AND archived_at>=created_at)))
);
CREATE UNIQUE INDEX lot_confirmed_identity_unique ON stockrescue.manufacturer_lot(org_id,product_id,manufacturer_identity_key,lot_code)
    WHERE identity_state='CONFIRMED';
CREATE INDEX lot_product_code_lookup_idx ON stockrescue.manufacturer_lot(org_id,product_id,lot_code,id);
COMMENT ON TABLE stockrescue.manufacturer_lot IS 'Organization-wide manufacturer identity. Candidates never merge by lot code or unverified manufacturer claims; no store owns a lot.';
COMMENT ON COLUMN stockrescue.manufacturer_lot.manufacturer_identity_key IS 'Exact case-sensitive manufacturer reference, not a display name. Candidate claims remain unverified; confirmation requires recorded source evidence.';
COMMENT ON COLUMN stockrescue.manufacturer_lot.labeled_value IS 'Original date label, retained verbatim even when incomplete or unparseable. SQL does not infer a date from it.';
COMMENT ON CONSTRAINT lot_cutoff_requires_policy_registry ON stockrescue.manufacturer_lot IS
    'Fail closed until a versioned policy registry exists. The policy migration must replace this guard with an organization-scoped policy-version FK before persisting normalized cutoffs.';
COMMENT ON COLUMN stockrescue.manufacturer_lot.recall_state IS 'NOT_REPORTED describes source evidence only and is not a safety approval.';

CREATE TABLE stockrescue.consignment_evidence (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    lot_id UUID NOT NULL,
    store_id UUID,
    supplier_name VARCHAR(160),
    supplier_reference VARCHAR(256),
    provenance_source VARCHAR(256) NOT NULL,
    provenance_reference VARCHAR(256),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    archived_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT consignment_org_identity UNIQUE (org_id,id),
    CONSTRAINT consignment_lot_fk FOREIGN KEY (org_id,lot_id) REFERENCES stockrescue.manufacturer_lot(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT consignment_store_fk FOREIGN KEY (org_id,store_id) REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT consignment_source_valid CHECK (length(btrim(provenance_source))>0 AND provenance_source COLLATE "C" !~ '[[:cntrl:]]'),
    CONSTRAINT consignment_source_reference_valid CHECK (provenance_reference IS NULL OR
        (length(btrim(provenance_reference))>0 AND provenance_reference COLLATE "C" !~ '[[:cntrl:]]')),
    CONSTRAINT consignment_supplier_valid CHECK (supplier_name IS NULL OR
        (length(btrim(supplier_name))>0 AND supplier_name COLLATE "C" !~ '[[:cntrl:]]')),
    CONSTRAINT consignment_supplier_reference_valid CHECK (supplier_reference IS NULL OR
        (length(btrim(supplier_reference))>0 AND supplier_reference COLLATE "C" !~ '[[:cntrl:]]')),
    CONSTRAINT consignment_version_valid CHECK (version>=0),
    CONSTRAINT consignment_lifecycle_valid CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at>=created_at
        AND ((NOT active)=(archived_at IS NOT NULL)) AND (archived_at IS NULL OR (isfinite(archived_at) AND archived_at>=created_at)))
);
CREATE INDEX consignment_lot_trace_idx ON stockrescue.consignment_evidence(org_id,lot_id,created_at,id);
CREATE INDEX consignment_store_trace_idx ON stockrescue.consignment_evidence(org_id,store_id,lot_id,created_at,id) WHERE store_id IS NOT NULL;
COMMENT ON TABLE stockrescue.consignment_evidence IS 'Independent source metadata, not a goods receipt, quantity, physical custody or stock movement. Supplier/source references are deliberately not unique.';
COMMENT ON COLUMN stockrescue.consignment_evidence.store_id IS 'Optional metadata observation scope; does not transfer organization-wide ownership of the manufacturer lot.';

CREATE FUNCTION stockrescue.validate_catalog_lifecycle() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.id,NEW.org_id,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.created_at)
        OR NOT OLD.active OR NEW.version<>OLD.version+1
        OR NEW.archived_at IS DISTINCT FROM OLD.archived_at THEN
        RAISE EXCEPTION 'Catalog identity is immutable; active updates require the next version' USING ERRCODE='23514';
    END IF;
    NEW.updated_at := GREATEST(clock_timestamp(),OLD.updated_at);
    IF NOT NEW.active THEN NEW.archived_at := NEW.updated_at; END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION stockrescue.validate_product_identity() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF NEW.sku IS DISTINCT FROM OLD.sku THEN
        RAISE EXCEPTION 'Product SKU identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION stockrescue.validate_lot_identity() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.product_id,NEW.lot_code) IS DISTINCT FROM (OLD.product_id,OLD.lot_code)
        OR (OLD.identity_state='CONFIRMED' AND
            (NEW.identity_state,NEW.manufacturer_identity_key,NEW.confirmed_by_membership_id,NEW.confirmed_at)
            IS DISTINCT FROM (OLD.identity_state,OLD.manufacturer_identity_key,OLD.confirmed_by_membership_id,OLD.confirmed_at)) THEN
        RAISE EXCEPTION 'Known lot identity and confirmation are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION stockrescue.validate_consignment_identity() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.lot_id,NEW.store_id) IS DISTINCT FROM (OLD.lot_id,OLD.store_id) THEN
        RAISE EXCEPTION 'Source lot and observation scope are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER product_lifecycle BEFORE UPDATE ON stockrescue.product FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_catalog_lifecycle();
CREATE TRIGGER product_identity BEFORE UPDATE ON stockrescue.product FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_product_identity();
CREATE TRIGGER product_no_delete BEFORE DELETE ON stockrescue.product FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER product_no_truncate BEFORE TRUNCATE ON stockrescue.product FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER manufacturer_lot_lifecycle BEFORE UPDATE ON stockrescue.manufacturer_lot FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_catalog_lifecycle();
CREATE TRIGGER manufacturer_lot_identity BEFORE UPDATE ON stockrescue.manufacturer_lot FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_lot_identity();
CREATE TRIGGER manufacturer_lot_no_delete BEFORE DELETE ON stockrescue.manufacturer_lot FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER manufacturer_lot_no_truncate BEFORE TRUNCATE ON stockrescue.manufacturer_lot FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER consignment_evidence_lifecycle BEFORE UPDATE ON stockrescue.consignment_evidence FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_catalog_lifecycle();
CREATE TRIGGER consignment_evidence_identity BEFORE UPDATE ON stockrescue.consignment_evidence FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_consignment_identity();
CREATE TRIGGER consignment_evidence_no_delete BEFORE DELETE ON stockrescue.consignment_evidence FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER consignment_evidence_no_truncate BEFORE TRUNCATE ON stockrescue.consignment_evidence FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE TABLE stockrescue.product_command (
    org_id UUID NOT NULL REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('CREATE','ARCHIVE')),
    request_id UUID NOT NULL,
    product_id UUID NOT NULL,
    result_version BIGINT NOT NULL CHECK (result_version>=0),
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT product_command_version_valid CHECK ((operation='CREATE' AND result_version=0) OR (operation='ARCHIVE' AND result_version>0)),
    PRIMARY KEY (org_id,actor_user_id,operation,request_id),
    CONSTRAINT product_command_result_fk FOREIGN KEY (org_id,product_id)
        REFERENCES stockrescue.product(org_id,id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);
COMMENT ON TABLE stockrescue.product_command IS 'Immutable successful product command outcomes. The unique key serializes retries; a creation reservation and its product commit together through the deferred result FK.';
CREATE INDEX product_command_result_idx ON stockrescue.product_command(org_id,product_id);
CREATE TRIGGER product_command_immutable BEFORE UPDATE OR DELETE ON stockrescue.product_command
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER product_command_no_truncate BEFORE TRUNCATE ON stockrescue.product_command
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE TABLE stockrescue.lot_registration (
    org_id UUID NOT NULL REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    lot_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (org_id,actor_user_id,request_id),
    CONSTRAINT lot_registration_result_fk FOREIGN KEY (org_id,lot_id)
        REFERENCES stockrescue.manufacturer_lot(org_id,id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX lot_registration_result_idx ON stockrescue.lot_registration(org_id,lot_id);
CREATE TRIGGER lot_registration_immutable BEFORE UPDATE OR DELETE ON stockrescue.lot_registration
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER lot_registration_no_truncate BEFORE TRUNCATE ON stockrescue.lot_registration
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE TABLE stockrescue.consignment_registration (
    org_id UUID NOT NULL REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (org_id,actor_user_id,request_id),
    CONSTRAINT consignment_registration_result_fk FOREIGN KEY (org_id,evidence_id)
        REFERENCES stockrescue.consignment_evidence(org_id,id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX consignment_registration_result_idx ON stockrescue.consignment_registration(org_id,evidence_id);
CREATE TRIGGER consignment_registration_immutable BEFORE UPDATE OR DELETE ON stockrescue.consignment_registration
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER consignment_registration_no_truncate BEFORE TRUNCATE ON stockrescue.consignment_registration
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

ALTER TABLE stockrescue.membership ADD CONSTRAINT membership_actor_identity UNIQUE (org_id,id,user_id);

CREATE OR REPLACE FUNCTION stockrescue.validate_lot_identity() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.product_id,NEW.lot_code) IS DISTINCT FROM (OLD.product_id,OLD.lot_code)
        OR (OLD.manufacturer_identity_key IS NOT NULL AND NEW.manufacturer_identity_key IS DISTINCT FROM OLD.manufacturer_identity_key)
        OR (OLD.manufacturer_source IS NOT NULL AND NEW.manufacturer_source IS DISTINCT FROM OLD.manufacturer_source
            AND NOT (OLD.identity_state='CANDIDATE' AND NEW.identity_state='CONFIRMED'))
        OR (OLD.identity_state='CONFIRMED' AND
            (NEW.identity_state,NEW.confirmed_by_membership_id,NEW.confirmed_at)
            IS DISTINCT FROM (OLD.identity_state,OLD.confirmed_by_membership_id,OLD.confirmed_at)) THEN
        RAISE EXCEPTION 'Known lot identity, source and confirmation are protected' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE stockrescue.lot_correction (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    lot_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    request_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    expected_version BIGINT NOT NULL CHECK (expected_version>=0),
    result_version BIGINT NOT NULL CHECK (result_version>0),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    before_evidence JSONB NOT NULL,
    after_evidence JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT lot_correction_next_version CHECK (result_version::numeric=expected_version::numeric+1),
    CONSTRAINT lot_correction_lot_fk FOREIGN KEY (org_id,lot_id)
        REFERENCES stockrescue.manufacturer_lot(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT lot_correction_actor_fk FOREIGN KEY (org_id,actor_membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT,
    CONSTRAINT lot_correction_request_unique UNIQUE (org_id,actor_user_id,request_id),
    CONSTRAINT lot_correction_version_unique UNIQUE (org_id,lot_id,result_version),
    CONSTRAINT lot_correction_snapshots_valid CHECK (coalesce(
        jsonb_typeof(before_evidence)='object' AND jsonb_typeof(after_evidence)='object'
        AND before_evidence->>'id'=lot_id::text AND after_evidence->>'id'=lot_id::text
        AND before_evidence->>'org_id'=org_id::text AND after_evidence->>'org_id'=org_id::text
        AND before_evidence->>'version'=expected_version::text AND after_evidence->>'version'=result_version::text,FALSE))
);
COMMENT ON TABLE stockrescue.lot_correction IS 'Immutable reviewed metadata correction and durable request result. Snapshots are exact PostgreSQL row evidence, not safety approvals.';
CREATE TRIGGER lot_correction_immutable BEFORE UPDATE OR DELETE ON stockrescue.lot_correction
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER lot_correction_no_truncate BEFORE TRUNCATE ON stockrescue.lot_correction
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE FUNCTION stockrescue.require_lot_correction_evidence() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.identity_state,NEW.manufacturer_identity_key,NEW.manufacturer_source,NEW.manufacturer_reference,
        NEW.confirmed_by_membership_id,NEW.confirmed_at,NEW.labeled_value,NEW.date_marking_type,NEW.date_precision,
        NEW.marking_source,NEW.marking_reference,NEW.normalized_cutoff,NEW.normalization_policy_version_id,
        NEW.normalization_policy_version,NEW.normalization_version)
        IS DISTINCT FROM
       (OLD.identity_state,OLD.manufacturer_identity_key,OLD.manufacturer_source,OLD.manufacturer_reference,
        OLD.confirmed_by_membership_id,OLD.confirmed_at,OLD.labeled_value,OLD.date_marking_type,OLD.date_precision,
        OLD.marking_source,OLD.marking_reference,OLD.normalized_cutoff,OLD.normalization_policy_version_id,
        OLD.normalization_policy_version,OLD.normalization_version)
       AND NOT EXISTS (
           SELECT 1 FROM stockrescue.lot_correction c WHERE c.org_id=NEW.org_id AND c.lot_id=NEW.id
             AND c.expected_version=OLD.version AND c.result_version=NEW.version
             AND c.before_evidence=to_jsonb(OLD) AND c.after_evidence=to_jsonb(NEW)
       ) THEN
        RAISE EXCEPTION 'Lot metadata changes require exact immutable correction evidence in the same transaction'
            USING ERRCODE='23514',CONSTRAINT='lot_correction_evidence_required';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER lot_correction_evidence_required
    AFTER UPDATE ON stockrescue.manufacturer_lot DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION stockrescue.require_lot_correction_evidence();

CREATE TABLE stockrescue.product_gtin_review_scope (
    org_id UUID NOT NULL REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    gtin VARCHAR(14) COLLATE "C" NOT NULL CHECK (stockrescue.catalog_gtin_valid(gtin)),
    version BIGINT NOT NULL CHECK (version>=0 AND version<9223372036854775807),
    PRIMARY KEY (org_id,gtin)
);

INSERT INTO stockrescue.product_gtin_review_scope(org_id,gtin,version)
SELECT org_id,gtin,count(*)::BIGINT
FROM stockrescue.product
WHERE gtin IS NOT NULL
GROUP BY org_id,gtin;

CREATE FUNCTION stockrescue.bump_product_gtin_review_scope() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.gtin IS NOT NULL THEN
            INSERT INTO stockrescue.product_gtin_review_scope(org_id,gtin,version)
            VALUES (NEW.org_id,NEW.gtin,1)
            ON CONFLICT (org_id,gtin) DO UPDATE
                SET version=stockrescue.product_gtin_review_scope.version+1;
        END IF;
        RETURN NEW;
    ELSIF TG_OP='UPDATE' THEN
        IF OLD.org_id IS DISTINCT FROM NEW.org_id OR OLD.gtin IS DISTINCT FROM NEW.gtin THEN
            IF OLD.gtin IS NOT NULL THEN
                UPDATE stockrescue.product_gtin_review_scope SET version=version+1
                WHERE org_id=OLD.org_id AND gtin=OLD.gtin;
            END IF;
            IF NEW.gtin IS NOT NULL THEN
                INSERT INTO stockrescue.product_gtin_review_scope(org_id,gtin,version)
                VALUES (NEW.org_id,NEW.gtin,1)
                ON CONFLICT (org_id,gtin) DO UPDATE
                    SET version=stockrescue.product_gtin_review_scope.version+1;
            END IF;
        ELSIF (OLD.id IS DISTINCT FROM NEW.id
                OR OLD.active IS DISTINCT FROM NEW.active
                OR OLD.sku_normalized IS DISTINCT FROM NEW.sku_normalized
                OR OLD.name IS DISTINCT FROM NEW.name) AND NEW.gtin IS NOT NULL THEN
            UPDATE stockrescue.product_gtin_review_scope SET version=version+1
            WHERE org_id=NEW.org_id AND gtin=NEW.gtin;
        END IF;
        RETURN NEW;
    ELSE
        IF OLD.gtin IS NOT NULL THEN
            UPDATE stockrescue.product_gtin_review_scope SET version=version+1
            WHERE org_id=OLD.org_id AND gtin=OLD.gtin;
        END IF;
        RETURN OLD;
    END IF;
END;
$$;

CREATE TRIGGER product_gtin_review_scope_insert AFTER INSERT ON stockrescue.product
    FOR EACH ROW EXECUTE FUNCTION stockrescue.bump_product_gtin_review_scope();
CREATE TRIGGER product_gtin_review_scope_update AFTER UPDATE OF id,org_id,gtin,active,sku_normalized,name ON stockrescue.product
    FOR EACH ROW EXECUTE FUNCTION stockrescue.bump_product_gtin_review_scope();
CREATE TRIGGER product_gtin_review_scope_delete AFTER DELETE ON stockrescue.product
    FOR EACH ROW EXECUTE FUNCTION stockrescue.bump_product_gtin_review_scope();

COMMENT ON TABLE stockrescue.product_gtin_review_scope IS
    'Per-organization exact GTIN serialization row. Its revision changes when a matching product is added, removed, renamed, recoded, moved or changes active state.';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM stockrescue.lot_registration
        GROUP BY org_id,request_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot enforce organization-scoped lot request identifiers while historical request IDs are duplicated';
    END IF;
END $$;

ALTER TABLE stockrescue.lot_registration DROP CONSTRAINT lot_registration_pkey;
ALTER TABLE stockrescue.lot_registration ADD CONSTRAINT lot_registration_pkey PRIMARY KEY (org_id,request_id);
ALTER TABLE stockrescue.lot_registration ADD COLUMN store_id UUID;
ALTER TABLE stockrescue.lot_registration ADD CONSTRAINT lot_registration_store_fk
    FOREIGN KEY (org_id,store_id) REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT;
CREATE INDEX lot_registration_store_idx ON stockrescue.lot_registration(org_id,store_id,recorded_at) WHERE store_id IS NOT NULL;

CREATE TABLE stockrescue.lot_archive_command (
    org_id UUID NOT NULL REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    lot_id UUID NOT NULL,
    result_version BIGINT NOT NULL CHECK (result_version>0),
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (org_id,actor_user_id,request_id),
    CONSTRAINT lot_archive_result_fk FOREIGN KEY (org_id,lot_id)
        REFERENCES stockrescue.manufacturer_lot(org_id,id) ON DELETE RESTRICT
);
CREATE INDEX lot_archive_command_result_idx ON stockrescue.lot_archive_command(org_id,lot_id);
COMMENT ON TABLE stockrescue.lot_archive_command IS 'Immutable successful manufacturer-lot archival outcomes. Request reservation, lot state and audit commit together.';

CREATE TRIGGER lot_archive_command_immutable BEFORE UPDATE OR DELETE ON stockrescue.lot_archive_command
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER lot_archive_command_no_truncate BEFORE TRUNCATE ON stockrescue.lot_archive_command
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE FUNCTION stockrescue.require_lot_archive_command() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF OLD.active AND NOT NEW.active AND NOT EXISTS (
        SELECT 1 FROM stockrescue.lot_archive_command c
        WHERE c.org_id=NEW.org_id AND c.lot_id=NEW.id AND c.result_version=NEW.version
    ) THEN
        RAISE EXCEPTION 'Manufacturer lot archival requires a durable command outcome' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER manufacturer_lot_archive_command BEFORE UPDATE ON stockrescue.manufacturer_lot
    FOR EACH ROW EXECUTE FUNCTION stockrescue.require_lot_archive_command();


-- Consolidated source: V010__food_safety_evidence_and_policy.sql
CREATE TABLE stockrescue.food_safety_policy_version (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    profile_id UUID NOT NULL,
    temperature_class VARCHAR(16) NOT NULL CHECK (temperature_class IN ('AMBIENT','CHILLED','FROZEN')),
    version BIGINT NOT NULL CHECK (version>0),
    partial_date_rule VARCHAR(32) NOT NULL CHECK (partial_date_rule IN ('NO_NORMALIZATION','END_OF_LABELED_PERIOD')),
    review_status VARCHAR(24) NOT NULL DEFAULT 'RECORDED_UNVERIFIED' CHECK (review_status='RECORDED_UNVERIFIED'),
    review_source VARCHAR(256) NOT NULL CHECK (length(btrim(review_source))>0 AND review_source COLLATE "C" !~ '[[:cntrl:]]'),
    review_reference VARCHAR(256) NOT NULL CHECK (length(btrim(review_reference))>0 AND review_reference COLLATE "C" !~ '[[:cntrl:]]'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    actor_user_id UUID NOT NULL,
    recorded_by_membership_id UUID NOT NULL,
    request_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT food_safety_policy_actor_fk FOREIGN KEY (org_id,recorded_by_membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT,
    CONSTRAINT food_safety_policy_version_unique UNIQUE (org_id,temperature_class,version),
    CONSTRAINT food_safety_policy_profile_version_unique UNIQUE (org_id,profile_id,version),
    CONSTRAINT food_safety_policy_request_unique UNIQUE (org_id,actor_user_id,request_id)
);
COMMENT ON TABLE stockrescue.food_safety_policy_version IS 'Immutable class-specific review evidence. Submitted references and requested partial-date rules are unverified; they cannot approve executable operations or date normalization.';
CREATE FUNCTION stockrescue.validate_food_safety_policy_append() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE previous stockrescue.food_safety_policy_version;
BEGIN
    -- Same organization-first lock as the policy write authority path; no lock upgrade in the application.
    PERFORM 1 FROM stockrescue.organization WHERE id=NEW.org_id FOR UPDATE;
    SELECT * INTO previous FROM stockrescue.food_safety_policy_version
        WHERE org_id=NEW.org_id AND temperature_class=NEW.temperature_class ORDER BY version DESC LIMIT 1;
    IF (NOT FOUND AND NEW.version<>1)
        OR (FOUND AND (NEW.profile_id<>previous.profile_id OR NEW.version::numeric<>previous.version::numeric+1)) THEN
        RAISE EXCEPTION 'Policy reviews require the next immutable version of the same profile' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER food_safety_policy_append BEFORE INSERT ON stockrescue.food_safety_policy_version
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_food_safety_policy_append();
CREATE TRIGGER food_safety_policy_immutable BEFORE UPDATE OR DELETE ON stockrescue.food_safety_policy_version
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER food_safety_policy_no_truncate BEFORE TRUNCATE ON stockrescue.food_safety_policy_version
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

ALTER TABLE stockrescue.lot_correction ADD COLUMN correction_kind VARCHAR(16) NOT NULL DEFAULT 'METADATA'
    CHECK (correction_kind IN ('METADATA','SAFETY_STATUS'));

CREATE OR REPLACE FUNCTION stockrescue.require_lot_correction_evidence() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE metadata_changed BOOLEAN; status_changed BOOLEAN;
BEGIN
    metadata_changed := (NEW.identity_state,NEW.manufacturer_identity_key,NEW.manufacturer_source,NEW.manufacturer_reference,
        NEW.confirmed_by_membership_id,NEW.confirmed_at,NEW.labeled_value,NEW.date_marking_type,NEW.date_precision,
        NEW.marking_source,NEW.marking_reference,NEW.normalized_cutoff,NEW.normalization_policy_version_id,
        NEW.normalization_policy_version,NEW.normalization_version)
        IS DISTINCT FROM
       (OLD.identity_state,OLD.manufacturer_identity_key,OLD.manufacturer_source,OLD.manufacturer_reference,
        OLD.confirmed_by_membership_id,OLD.confirmed_at,OLD.labeled_value,OLD.date_marking_type,OLD.date_precision,
        OLD.marking_source,OLD.marking_reference,OLD.normalized_cutoff,OLD.normalization_policy_version_id,
        OLD.normalization_policy_version,OLD.normalization_version);
    status_changed := (NEW.recall_state,NEW.damage_state,NEW.status_source,NEW.status_reference)
        IS DISTINCT FROM (OLD.recall_state,OLD.damage_state,OLD.status_source,OLD.status_reference);
    IF (OLD.recall_state='RECALLED' AND NEW.recall_state<>'RECALLED')
        OR (OLD.damage_state='DAMAGED' AND NEW.damage_state<>'DAMAGED') THEN
        RAISE EXCEPTION 'Recorded recall and damage blocks cannot be cleared by metadata edits' USING ERRCODE='23514';
    END IF;
    IF metadata_changed OR status_changed THEN
        IF metadata_changed AND status_changed OR NOT EXISTS (
            SELECT 1 FROM stockrescue.lot_correction c WHERE c.org_id=NEW.org_id AND c.lot_id=NEW.id
                AND c.expected_version=OLD.version AND c.result_version=NEW.version
                AND c.correction_kind=CASE WHEN status_changed THEN 'SAFETY_STATUS' ELSE 'METADATA' END
                AND c.before_evidence=to_jsonb(OLD) AND c.after_evidence=to_jsonb(NEW)
        ) THEN
            RAISE EXCEPTION 'Lot changes require exact immutable evidence of the matching kind in the same transaction'
                USING ERRCODE='23514',CONSTRAINT='lot_correction_evidence_required';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
COMMENT ON CONSTRAINT lot_cutoff_requires_policy_registry ON stockrescue.manufacturer_lot IS
    'Partial-date normalization remains blocked: policy review references are unverified. A verified rule and organization-scoped policy-version relationship are required before persisting cutoffs.';

ALTER TABLE stockrescue.storage_zone ADD CONSTRAINT storage_zone_active_identity UNIQUE (org_id,store_id,id,active);

CREATE TABLE stockrescue.storage_location (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    zone_id UUID NOT NULL,
    code VARCHAR(32) NOT NULL,
    code_normalized VARCHAR(32) COLLATE "C" GENERATED ALWAYS AS (upper(btrim(code) COLLATE "C")) STORED,
    name VARCHAR(160) NOT NULL CHECK (length(btrim(name))>0 AND name COLLATE "C" !~ '[[:cntrl:]]'),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    required_zone_active BOOLEAN GENERATED ALWAYS AS (CASE WHEN active THEN TRUE ELSE NULL::boolean END) STORED,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ,
    CONSTRAINT storage_location_code_valid CHECK (code_normalized ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT storage_location_lifecycle_valid CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at>=created_at
        AND ((NOT active)=(archived_at IS NOT NULL)) AND (archived_at IS NULL OR (isfinite(archived_at) AND archived_at>=created_at))),
    CONSTRAINT storage_location_org_identity UNIQUE (org_id,store_id,id),
    CONSTRAINT storage_location_store_code_unique UNIQUE (org_id,store_id,code_normalized),
    CONSTRAINT storage_location_zone_fk FOREIGN KEY (org_id,store_id,zone_id)
        REFERENCES stockrescue.storage_zone(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT storage_location_active_zone_fk FOREIGN KEY (org_id,store_id,zone_id,required_zone_active)
        REFERENCES stockrescue.storage_zone(org_id,store_id,id,active) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE INDEX storage_location_zone_impact_idx ON stockrescue.storage_location(org_id,store_id,zone_id,id) WHERE active;
COMMENT ON TABLE stockrescue.storage_location IS 'Stable store-scoped configuration identity for future references; contains no quantity, capacity, availability or movement claims. Archived stores retain readable but unusable location configuration.';
COMMENT ON CONSTRAINT storage_location_active_zone_fk ON stockrescue.storage_location IS
    'An active location references the active parent key. PostgreSQL referential checks serialize creation against zone archival, including repeatable-read transactions; archive locations first.';

CREATE FUNCTION stockrescue.validate_storage_location() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE parent_active BOOLEAN;
BEGIN
    IF TG_OP='UPDATE' THEN
        IF (NEW.id,NEW.org_id,NEW.store_id,NEW.zone_id,NEW.code,NEW.created_at)
            IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.store_id,OLD.zone_id,OLD.code,OLD.created_at)
            OR NOT OLD.active OR NEW.version<>OLD.version+1 OR NEW.archived_at IS DISTINCT FROM OLD.archived_at THEN
            RAISE EXCEPTION 'Location identity is fixed; active updates require the next version' USING ERRCODE='23514';
        END IF;
        NEW.updated_at := GREATEST(clock_timestamp(),OLD.updated_at);
        IF NOT NEW.active THEN NEW.archived_at := NEW.updated_at; END IF;
    END IF;
    IF NEW.active THEN
        SELECT active INTO parent_active FROM stockrescue.store WHERE org_id=NEW.org_id AND id=NEW.store_id FOR SHARE;
        IF FOUND AND NOT parent_active THEN
            RAISE EXCEPTION 'Active location writes require an active store' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER storage_location_valid BEFORE INSERT OR UPDATE ON stockrescue.storage_location
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_storage_location();
CREATE TRIGGER storage_location_no_delete BEFORE DELETE ON stockrescue.storage_location
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER storage_location_no_truncate BEFORE TRUNCATE ON stockrescue.storage_location
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE TABLE stockrescue.storage_location_command (
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('CREATE','RENAME','ARCHIVE')),
    request_id UUID NOT NULL,
    location_id UUID NOT NULL,
    result_version BIGINT NOT NULL CHECK (result_version>=0),
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (org_id,actor_user_id,operation,request_id),
    CONSTRAINT storage_location_command_version_valid CHECK ((operation='CREATE' AND result_version=0) OR (operation IN ('RENAME','ARCHIVE') AND result_version>0)),
    CONSTRAINT storage_location_command_result_fk FOREIGN KEY (org_id,store_id,location_id)
        REFERENCES stockrescue.storage_location(org_id,store_id,id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX storage_location_command_result_idx ON stockrescue.storage_location_command(org_id,store_id,location_id);
CREATE TRIGGER storage_location_command_immutable BEFORE UPDATE OR DELETE ON stockrescue.storage_location_command
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER storage_location_command_no_truncate BEFORE TRUNCATE ON stockrescue.storage_location_command
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

ALTER TABLE stockrescue.product
    ADD COLUMN category VARCHAR(24) NOT NULL DEFAULT 'UNCLASSIFIED',
    ADD CONSTRAINT product_category_supported CHECK (category IN ('UNCLASSIFIED','DAIRY','BAKERY'));
COMMENT ON COLUMN stockrescue.product.category IS
    'Descriptive catalog grouping only; it is not a legal classification or food-safety approval.';

ALTER TABLE stockrescue.food_safety_policy_version
    ADD COLUMN product_category VARCHAR(24) NOT NULL DEFAULT 'UNCLASSIFIED',
    ADD CONSTRAINT food_safety_policy_category_supported CHECK (product_category IN ('UNCLASSIFIED','DAIRY','BAKERY'));

ALTER TABLE stockrescue.food_safety_policy_version
    DROP CONSTRAINT food_safety_policy_version_unique;
ALTER TABLE stockrescue.food_safety_policy_version
    ADD CONSTRAINT food_safety_policy_version_unique UNIQUE (org_id,product_category,temperature_class,version);

CREATE OR REPLACE FUNCTION stockrescue.validate_food_safety_policy_append() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE previous stockrescue.food_safety_policy_version;
BEGIN
    -- Same organization-first lock as the policy write authority path; no lock upgrade in the application.
    PERFORM 1 FROM stockrescue.organization WHERE id=NEW.org_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM stockrescue.food_safety_policy_version p
        WHERE p.org_id=NEW.org_id AND p.profile_id=NEW.profile_id
          AND (p.product_category,p.temperature_class) IS DISTINCT FROM (NEW.product_category,NEW.temperature_class)) THEN
        RAISE EXCEPTION 'A policy profile cannot be reused across category or temperature scopes' USING ERRCODE='23514';
    END IF;
    SELECT * INTO previous FROM stockrescue.food_safety_policy_version
        WHERE org_id=NEW.org_id AND product_category=NEW.product_category AND temperature_class=NEW.temperature_class
        ORDER BY version DESC LIMIT 1;
    IF (NOT FOUND AND NEW.version<>1)
        OR (FOUND AND (NEW.profile_id<>previous.profile_id OR NEW.version::numeric<>previous.version::numeric+1)) THEN
        RAISE EXCEPTION 'Policy reviews require the next immutable version of the same scoped profile' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE stockrescue.product_command DROP CONSTRAINT product_command_operation_check;
ALTER TABLE stockrescue.product_command ADD CONSTRAINT product_command_operation_check
    CHECK (operation IN ('CREATE','ARCHIVE'));
ALTER TABLE stockrescue.product_command DROP CONSTRAINT product_command_version_valid;
ALTER TABLE stockrescue.product_command ADD CONSTRAINT product_command_version_valid CHECK (
    (operation='CREATE' AND result_version=0) OR (operation='ARCHIVE' AND result_version>0));

COMMENT ON TABLE stockrescue.food_safety_policy_version IS
    'Immutable review history scoped by organization, descriptive product category and storage temperature class. Review evidence remains unverified and cannot approve executable operations or date normalization.';


-- Consolidated source: V011__operator_classification_confirmation.sql
ALTER TABLE stockrescue.product_command DROP CONSTRAINT product_command_operation_check;
ALTER TABLE stockrescue.product_command ALTER COLUMN operation TYPE VARCHAR(32);
ALTER TABLE stockrescue.product_command ADD CONSTRAINT product_command_operation_check
    CHECK (operation IN ('CREATE','ARCHIVE','CONFIRM_CLASSIFICATION'));
ALTER TABLE stockrescue.product_command DROP CONSTRAINT product_command_version_valid;
ALTER TABLE stockrescue.product_command ADD CONSTRAINT product_command_version_valid CHECK (
    (operation='CREATE' AND result_version=0)
    OR (operation IN ('ARCHIVE','CONFIRM_CLASSIFICATION') AND result_version>0));

CREATE TABLE stockrescue.food_safety_product_classification_confirmation (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    product_id UUID NOT NULL,
    sku VARCHAR(64) COLLATE "C" NOT NULL,
    expected_product_version BIGINT NOT NULL CHECK (expected_product_version>=0),
    product_version BIGINT NOT NULL CHECK (product_version>0),
    original_category VARCHAR(24) NOT NULL CHECK (original_category IN ('UNCLASSIFIED','DAIRY','BAKERY')),
    confirmed_category VARCHAR(24) NOT NULL CHECK (confirmed_category IN ('UNCLASSIFIED','DAIRY','BAKERY')),
    original_temperature_class VARCHAR(16) NOT NULL CHECK (original_temperature_class IN ('AMBIENT','CHILLED','FROZEN')),
    confirmed_temperature_class VARCHAR(16) NOT NULL CHECK (confirmed_temperature_class IN ('AMBIENT','CHILLED','FROZEN')),
    product_profile VARCHAR(48) NOT NULL CHECK (product_profile IN (
        'NOT_ASSESSED','PACKAGED_SHELF_STABLE_DRY_BAKERY','REFRIGERATION_REQUIRED',
        'CREAM_FILLED_OR_PERISHABLE','NOT_SHELF_STABLE','STORAGE_INSTRUCTION_MISSING_OR_AMBIGUOUS',
        'CONTRADICTORY_STORAGE_EVIDENCE','OUTSIDE_CURRENT_PILOT')),
    source_type VARCHAR(32) CHECK (source_type IN ('ORIGINAL_PACKAGE','SUPPLIER_DOCUMENT','OTHER')),
    evidence_reference VARCHAR(256) COLLATE "C",
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    recorded_by_membership_id UUID NOT NULL,
    request_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT food_safety_product_classification_confirmation_version_valid
        CHECK (product_version=expected_product_version+1),
    CONSTRAINT food_safety_product_classification_confirmation_source_valid CHECK (
        (source_type IS NULL AND evidence_reference IS NULL)
        OR (source_type IS NOT NULL AND evidence_reference IS NOT NULL
            AND length(btrim(evidence_reference))>0 AND evidence_reference COLLATE "C" !~ '[[:cntrl:]]')),
    CONSTRAINT food_safety_product_classification_confirmation_source_required CHECK (source_type IS NOT NULL),
    CONSTRAINT food_safety_product_classification_confirmation_product_fk FOREIGN KEY (org_id,product_id)
        REFERENCES stockrescue.product(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT food_safety_product_classification_confirmation_actor_fk FOREIGN KEY (org_id,recorded_by_membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT,
    CONSTRAINT food_safety_product_classification_confirmation_request_unique UNIQUE (org_id,actor_user_id,request_id),
    CONSTRAINT food_safety_product_classification_confirmation_org_id_unique UNIQUE (org_id,id)
);
CREATE INDEX food_safety_product_classification_confirmation_latest_idx
    ON stockrescue.food_safety_product_classification_confirmation(org_id,product_id,product_version DESC,recorded_at DESC,id DESC);
CREATE TRIGGER food_safety_product_classification_confirmation_immutable
    BEFORE UPDATE OR DELETE ON stockrescue.food_safety_product_classification_confirmation
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER food_safety_product_classification_confirmation_no_truncate
    BEFORE TRUNCATE ON stockrescue.food_safety_product_classification_confirmation
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
COMMENT ON TABLE stockrescue.food_safety_product_classification_confirmation IS
    'Immutable operator confirmation of descriptive category, declared temperature and bounded product profile with cited evidence, actor, reason, durable request and exact resulting product version; never independent or specialist verification.';


-- Consolidated source: V012__inventory_ledger.sql
ALTER TABLE stockrescue.manufacturer_lot ADD CONSTRAINT lot_inventory_identity UNIQUE (org_id,product_id,id);

CREATE TABLE stockrescue.stock_position (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    location_id UUID NOT NULL,
    product_id UUID NOT NULL,
    lot_id UUID NOT NULL,
    unit VARCHAR(8) NOT NULL DEFAULT 'EA' CHECK (unit='EA'),
    initialization_state VARCHAR(24) NOT NULL DEFAULT 'NOT_INITIALIZED'
        CHECK (initialization_state IN ('NOT_INITIALIZED','INITIALIZED')),
    trust_state VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED' CHECK (trust_state IN ('UNVERIFIED','CLEAN','FROZEN')),
    on_hand BIGINT NOT NULL DEFAULT 0 CHECK (on_hand>=0),
    reserved BIGINT NOT NULL DEFAULT 0 CHECK (reserved>=0),
    quarantined BIGINT NOT NULL DEFAULT 0 CHECK (quarantined>=0),
    other_blocked BIGINT NOT NULL DEFAULT 0 CHECK (other_blocked>=0),
    available BIGINT GENERATED ALWAYS AS (on_hand-reserved-quarantined-other_blocked) STORED,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(created_at)),
    CONSTRAINT stock_position_buckets_valid CHECK (reserved::numeric+quarantined+other_blocked<=on_hand),
    CONSTRAINT stock_position_unknown_empty CHECK (initialization_state='INITIALIZED'
        OR (on_hand=0 AND reserved=0 AND quarantined=0 AND other_blocked=0)),
    CONSTRAINT stock_position_initialization_version CHECK ((initialization_state='NOT_INITIALIZED')=(version=0)),
    CONSTRAINT stock_position_identity UNIQUE (org_id,store_id,location_id,lot_id),
    CONSTRAINT stock_position_scope UNIQUE (org_id,store_id,id),
    CONSTRAINT stock_position_location_fk FOREIGN KEY (org_id,store_id,location_id)
        REFERENCES stockrescue.storage_location(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT stock_position_lot_fk FOREIGN KEY (org_id,product_id,lot_id)
        REFERENCES stockrescue.manufacturer_lot(org_id,product_id,id) ON DELETE RESTRICT
);
CREATE INDEX stock_position_product_page_idx ON stockrescue.stock_position(org_id,store_id,product_id,lot_id,location_id,id);

CREATE FUNCTION stockrescue.guard_stock_position() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.version<>0 OR NEW.initialization_state<>'NOT_INITIALIZED' OR NEW.trust_state<>'UNVERIFIED'
            OR NEW.on_hand<>0 OR NEW.reserved<>0 OR NEW.quarantined<>0 OR NEW.other_blocked<>0 THEN
            RAISE EXCEPTION 'A new position requires uninitialized zero' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (NEW.id,NEW.org_id,NEW.store_id,NEW.location_id,NEW.product_id,NEW.lot_id,NEW.unit,NEW.created_at)
            IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.store_id,OLD.location_id,OLD.product_id,OLD.lot_id,OLD.unit,OLD.created_at)
            OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1
            OR (OLD.initialization_state='INITIALIZED' AND NEW.initialization_state<>'INITIALIZED') THEN
            RAISE EXCEPTION 'Position identity and monotonic version are required' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM stockrescue.stock_movement_line l
            WHERE l.org_id=NEW.org_id AND l.store_id=NEW.store_id AND l.position_id=NEW.id AND l.position_version=NEW.version
              AND OLD.on_hand::numeric+l.delta_on_hand=NEW.on_hand
              AND OLD.reserved::numeric+l.delta_reserved=NEW.reserved
              AND OLD.quarantined::numeric+l.delta_quarantined=NEW.quarantined
              AND OLD.other_blocked::numeric+l.delta_other_blocked=NEW.other_blocked
              AND l.initialization_after=NEW.initialization_state AND l.trust_after=NEW.trust_state) THEN
            RAISE EXCEPTION 'A projection update requires matching immutable movement evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER stock_position_guard BEFORE INSERT OR UPDATE ON stockrescue.stock_position
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_stock_position();

CREATE TABLE stockrescue.inventory_command_receipt (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    operation VARCHAR(24) NOT NULL CHECK (operation IN
        ('RECEIVE','RESERVE','RELEASE','ADJUST','WRITE_OFF','QUARANTINE','HOLD','RELEASE_HOLD','SALE','VERIFY_ZERO','RECONCILE','REMEDIATE',
         'TRANSFER_RESERVE','TRANSFER_RELEASE','TRANSFER_OUT','TRANSFER_IN','TRANSFER_RETURN')),
    request_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    outcome JSONB NOT NULL CHECK (jsonb_typeof(outcome)='object'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT inventory_command_request_unique UNIQUE (org_id,actor_user_id,operation,request_id),
    CONSTRAINT inventory_command_scope UNIQUE (org_id,store_id,id,operation),
    CONSTRAINT inventory_command_store_fk FOREIGN KEY (org_id,store_id) REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT inventory_command_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT
);

CREATE TABLE stockrescue.stock_movement (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    command_id UUID NOT NULL,
    operation VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    evidence_reference VARCHAR(256) NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference COLLATE "C" !~ '[[:cntrl:]]'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT stock_movement_command_unique UNIQUE (org_id,store_id,command_id),
    CONSTRAINT stock_movement_scope UNIQUE (org_id,store_id,id),
    CONSTRAINT stock_movement_operation_scope UNIQUE (org_id,store_id,id,operation),
    CONSTRAINT stock_movement_command_fk FOREIGN KEY (org_id,store_id,command_id,operation)
        REFERENCES stockrescue.inventory_command_receipt(org_id,store_id,id,operation) ON DELETE RESTRICT
);
CREATE INDEX stock_movement_history_idx ON stockrescue.stock_movement(org_id,store_id,recorded_at,id);

CREATE TABLE stockrescue.stock_movement_line (
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    movement_id UUID NOT NULL,
    position_id UUID NOT NULL,
    position_version BIGINT NOT NULL CHECK (position_version>0),
    delta_on_hand BIGINT NOT NULL CHECK (delta_on_hand<>-9223372036854775808),
    delta_reserved BIGINT NOT NULL CHECK (delta_reserved<>-9223372036854775808),
    delta_quarantined BIGINT NOT NULL CHECK (delta_quarantined<>-9223372036854775808),
    delta_other_blocked BIGINT NOT NULL CHECK (delta_other_blocked<>-9223372036854775808),
    initialization_after VARCHAR(24) NOT NULL CHECK (initialization_after='INITIALIZED'),
    trust_after VARCHAR(16) NOT NULL CHECK (trust_after IN ('UNVERIFIED','CLEAN','FROZEN')),
    PRIMARY KEY (org_id,store_id,movement_id,position_id),
    CONSTRAINT stock_line_version_unique UNIQUE (org_id,store_id,position_id,position_version),
    CONSTRAINT stock_line_event_identity UNIQUE (org_id,store_id,movement_id,position_id,position_version),
    CONSTRAINT stock_line_movement_fk FOREIGN KEY (org_id,store_id,movement_id)
        REFERENCES stockrescue.stock_movement(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT stock_line_position_fk FOREIGN KEY (org_id,store_id,position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT
);

CREATE TABLE stockrescue.goods_receipt (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    movement_id UUID NOT NULL,
    operation VARCHAR(24) NOT NULL DEFAULT 'RECEIVE' CHECK (operation='RECEIVE'),
    supplier_key VARCHAR(200) COLLATE "C" NOT NULL CHECK (supplier_key=btrim(supplier_key) AND length(supplier_key)>0 AND supplier_key !~ '[[:cntrl:]]'),
    document_type VARCHAR(32) COLLATE "C" NOT NULL CHECK (document_type=btrim(document_type) AND length(document_type)>0 AND document_type !~ '[[:cntrl:]]'),
    document_reference VARCHAR(200) COLLATE "C" NOT NULL CHECK (document_reference=btrim(document_reference) AND length(document_reference)>0 AND document_reference !~ '[[:cntrl:]]'),
    physical_receipt_at TIMESTAMPTZ NOT NULL CHECK (isfinite(physical_receipt_at)),
    CONSTRAINT goods_receipt_document_unique UNIQUE (org_id,supplier_key,document_type,document_reference),
    CONSTRAINT goods_receipt_scope UNIQUE (org_id,store_id,id,movement_id),
    CONSTRAINT goods_receipt_movement_unique UNIQUE (org_id,store_id,movement_id),
    CONSTRAINT goods_receipt_movement_fk FOREIGN KEY (org_id,store_id,movement_id,operation)
        REFERENCES stockrescue.stock_movement(org_id,store_id,id,operation) ON DELETE RESTRICT
);
CREATE TABLE stockrescue.goods_receipt_line (
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    receipt_id UUID NOT NULL,
    movement_id UUID NOT NULL,
    position_id UUID NOT NULL,
    document_line_reference VARCHAR(64) COLLATE "C" NOT NULL CHECK (document_line_reference=btrim(document_line_reference)
        AND length(document_line_reference)>0 AND document_line_reference !~ '[[:cntrl:]]'),
    quantity BIGINT NOT NULL CHECK (quantity>0),
    disposition VARCHAR(16) NOT NULL CHECK (disposition IN ('AVAILABLE','QUARANTINED','OTHER_BLOCKED')),
    PRIMARY KEY (org_id,receipt_id,document_line_reference),
    CONSTRAINT receipt_line_receipt_fk FOREIGN KEY (org_id,store_id,receipt_id,movement_id)
        REFERENCES stockrescue.goods_receipt(org_id,store_id,id,movement_id) ON DELETE RESTRICT,
    CONSTRAINT receipt_line_movement_fk FOREIGN KEY (org_id,store_id,movement_id,position_id)
        REFERENCES stockrescue.stock_movement_line(org_id,store_id,movement_id,position_id) ON DELETE RESTRICT
);

CREATE TABLE stockrescue.stock_allocation (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    position_id UUID NOT NULL,
    bucket VARCHAR(16) NOT NULL CHECK (bucket IN ('RESERVED','QUARANTINED','OTHER_BLOCKED')),
    owner_user_id UUID NOT NULL,
    owner_membership_id UUID NOT NULL,
    initial_quantity BIGINT NOT NULL CHECK (initial_quantity>0),
    remaining_quantity BIGINT NOT NULL CHECK (remaining_quantity>=0 AND remaining_quantity<=initial_quantity),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','RELEASED','CONSUMED','WRITTEN_OFF')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
    CONSTRAINT stock_allocation_state_valid CHECK ((state='ACTIVE')=(remaining_quantity>0)),
    CONSTRAINT stock_allocation_scope UNIQUE (org_id,store_id,id,position_id,bucket),
    CONSTRAINT stock_allocation_position_fk FOREIGN KEY (org_id,store_id,position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT stock_allocation_owner_fk FOREIGN KEY (org_id,owner_membership_id,owner_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT
);
CREATE INDEX stock_allocation_active_idx ON stockrescue.stock_allocation(org_id,store_id,position_id,bucket,id) WHERE state='ACTIVE';
CREATE TABLE stockrescue.stock_allocation_transition (
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    allocation_id UUID NOT NULL,
    position_id UUID NOT NULL,
    bucket VARCHAR(16) NOT NULL,
    movement_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version>=0),
    previous_version BIGINT GENERATED ALWAYS AS (CASE WHEN version>0 THEN version-1 ELSE NULL END) STORED,
    remaining_before BIGINT NOT NULL CHECK (remaining_before>=0),
    remaining_after BIGINT NOT NULL CHECK (remaining_after>=0),
    state_after VARCHAR(16) NOT NULL CHECK (state_after IN ('ACTIVE','RELEASED','CONSUMED','WRITTEN_OFF')),
    transition VARCHAR(16) NOT NULL CHECK (transition IN ('CREATE','RELEASE','CONSUME','WRITE_OFF')),
    PRIMARY KEY (org_id,store_id,allocation_id,version),
    CONSTRAINT allocation_transition_state CHECK ((state_after='ACTIVE')=(remaining_after>0)),
    CONSTRAINT allocation_transition_amount CHECK (
        (version=0 AND transition='CREATE' AND remaining_before=0 AND remaining_after>0 AND state_after='ACTIVE')
        OR (version>0 AND transition<>'CREATE' AND remaining_after<remaining_before)),
    CONSTRAINT allocation_transition_result UNIQUE (org_id,store_id,allocation_id,version,remaining_after,state_after),
    CONSTRAINT allocation_transition_quantity UNIQUE (org_id,store_id,allocation_id,version,remaining_after),
    CONSTRAINT allocation_transition_previous_fk FOREIGN KEY (org_id,store_id,allocation_id,previous_version,remaining_before)
        REFERENCES stockrescue.stock_allocation_transition(org_id,store_id,allocation_id,version,remaining_after) ON DELETE RESTRICT,
    CONSTRAINT allocation_transition_closure CHECK (remaining_after>0
        OR (transition='RELEASE' AND state_after='RELEASED') OR (transition='CONSUME' AND state_after='CONSUMED')
        OR (transition='WRITE_OFF' AND state_after='WRITTEN_OFF')),
    CONSTRAINT allocation_transition_allocation_fk FOREIGN KEY (org_id,store_id,allocation_id,position_id,bucket)
        REFERENCES stockrescue.stock_allocation(org_id,store_id,id,position_id,bucket) ON DELETE RESTRICT,
    CONSTRAINT allocation_transition_movement_fk FOREIGN KEY (org_id,store_id,movement_id,position_id)
        REFERENCES stockrescue.stock_movement_line(org_id,store_id,movement_id,position_id) ON DELETE RESTRICT
);
ALTER TABLE stockrescue.stock_allocation ADD CONSTRAINT stock_allocation_current_evidence_fk
    FOREIGN KEY (org_id,store_id,id,version,remaining_quantity,state)
    REFERENCES stockrescue.stock_allocation_transition(org_id,store_id,allocation_id,version,remaining_after,state_after)
    DEFERRABLE INITIALLY DEFERRED;
CREATE FUNCTION stockrescue.guard_stock_allocation() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.version<>0 OR NEW.state<>'ACTIVE' OR NEW.remaining_quantity<>NEW.initial_quantity THEN
            RAISE EXCEPTION 'Allocation must start active at its full quantity' USING ERRCODE='23514';
        END IF;
    ELSIF (NEW.id,NEW.org_id,NEW.store_id,NEW.position_id,NEW.bucket,NEW.owner_user_id,NEW.owner_membership_id,NEW.initial_quantity)
        IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.store_id,OLD.position_id,OLD.bucket,OLD.owner_user_id,OLD.owner_membership_id,OLD.initial_quantity)
        OR OLD.state<>'ACTIVE' OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1
        OR NEW.remaining_quantity>=OLD.remaining_quantity THEN
        RAISE EXCEPTION 'Allocation identity and one-way lifecycle are required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER stock_allocation_guard BEFORE INSERT OR UPDATE ON stockrescue.stock_allocation
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_stock_allocation();

CREATE FUNCTION stockrescue.check_inventory_line_evidence() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE line record; ownership record;
BEGIN
    SELECT l.* INTO STRICT line FROM stockrescue.stock_movement_line l
        WHERE l.org_id=NEW.org_id AND l.store_id=NEW.store_id AND l.movement_id=NEW.movement_id AND l.position_id=NEW.position_id;
    IF NOT EXISTS (SELECT 1 FROM stockrescue.stock_position p WHERE p.org_id=line.org_id AND p.store_id=line.store_id
        AND p.id=line.position_id AND p.version>=line.position_version) THEN
        RAISE EXCEPTION 'Movement evidence must have a committed projection' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM stockrescue.inventory_outbox_event e
        JOIN stockrescue.inventory_outbox_delivery d ON d.event_id=e.id AND d.org_id=e.org_id AND d.store_id=e.store_id
        WHERE e.org_id=line.org_id AND e.store_id=line.store_id AND e.position_id=line.position_id
            AND e.aggregate_version=line.position_version AND e.movement_id=line.movement_id) THEN
        RAISE EXCEPTION 'Movement evidence requires a durable pending event' USING ERRCODE='23514';
    END IF;
    SELECT coalesce(sum(remaining_after::numeric-remaining_before) FILTER (WHERE bucket='RESERVED'),0) AS reserved,
        coalesce(sum(remaining_after::numeric-remaining_before) FILTER (WHERE bucket='QUARANTINED'),0) AS quarantined,
        coalesce(sum(remaining_after::numeric-remaining_before) FILTER (WHERE bucket='OTHER_BLOCKED'),0) AS blocked
        INTO ownership FROM stockrescue.stock_allocation_transition
        WHERE org_id=line.org_id AND store_id=line.store_id AND movement_id=line.movement_id AND position_id=line.position_id;
    IF (ownership.reserved,ownership.quarantined,ownership.blocked)
        IS DISTINCT FROM (line.delta_reserved::numeric,line.delta_quarantined::numeric,line.delta_other_blocked::numeric) THEN
        RAISE EXCEPTION 'Bucket changes require matching disjoint allocation evidence' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='stock_allocation_transition' THEN
        IF NOT EXISTS (SELECT 1 FROM stockrescue.stock_allocation a
            WHERE a.org_id=NEW.org_id AND a.store_id=NEW.store_id AND a.id=NEW.allocation_id AND a.version>=NEW.version
                AND (NEW.version>0 OR NEW.remaining_after=a.initial_quantity)) THEN
            RAISE EXCEPTION 'Allocation transition requires matching current ownership' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER stock_line_evidence AFTER INSERT ON stockrescue.stock_movement_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION stockrescue.check_inventory_line_evidence();
CREATE CONSTRAINT TRIGGER allocation_line_evidence AFTER INSERT ON stockrescue.stock_allocation_transition
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION stockrescue.check_inventory_line_evidence();

CREATE TABLE stockrescue.inventory_reconciliation_run (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    severity VARCHAR(8) NOT NULL CHECK (severity IN ('CLEAN','WARN','CRITICAL')),
    checked_positions INTEGER NOT NULL CHECK (checked_positions>=0),
    started_at TIMESTAMPTZ NOT NULL CHECK (isfinite(started_at)),
    completed_at TIMESTAMPTZ NOT NULL CHECK (isfinite(completed_at) AND completed_at>=started_at),
    CONSTRAINT reconciliation_run_scope UNIQUE (org_id,store_id,id),
    CONSTRAINT reconciliation_run_store_fk FOREIGN KEY (org_id,store_id) REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT reconciliation_run_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT
);
CREATE TABLE stockrescue.inventory_reconciliation_result (
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    run_id UUID NOT NULL,
    position_id UUID NOT NULL,
    position_version BIGINT NOT NULL CHECK (position_version>=0),
    severity VARCHAR(8) NOT NULL CHECK (severity IN ('CLEAN','WARN','CRITICAL')),
    finding_code VARCHAR(64) NOT NULL CHECK (finding_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    expected_buckets JSONB NOT NULL CHECK (jsonb_typeof(expected_buckets)='object'),
    observed_buckets JSONB NOT NULL CHECK (jsonb_typeof(observed_buckets)='object'),
    PRIMARY KEY (org_id,store_id,run_id,position_id),
    CONSTRAINT reconciliation_result_run_fk FOREIGN KEY (org_id,store_id,run_id)
        REFERENCES stockrescue.inventory_reconciliation_run(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT reconciliation_result_position_fk FOREIGN KEY (org_id,store_id,position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT
);

CREATE TABLE stockrescue.inventory_outbox_event (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    movement_id UUID NOT NULL,
    position_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version>0),
    event_type VARCHAR(32) NOT NULL CHECK (event_type='InventoryStockChanged'),
    schema_version INTEGER NOT NULL CHECK (schema_version=1),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='object'),
    correlation_id VARCHAR(64),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT inventory_outbox_scope UNIQUE (org_id,store_id,id),
    CONSTRAINT inventory_outbox_consumer_identity UNIQUE (org_id,store_id,id,position_id,aggregate_version),
    CONSTRAINT inventory_outbox_envelope CHECK (payload ?& ARRAY['eventId','schemaVersion','eventType','organizationId','storeId','positionId','aggregateVersion','movementId','occurredAt','change','correlationId']
        AND coalesce(payload->>'eventId'=id::text AND payload->>'schemaVersion'='1' AND payload->>'eventType'=event_type
        AND payload->>'organizationId'=org_id::text AND payload->>'storeId'=store_id::text
        AND payload->>'positionId'=position_id::text AND payload->>'aggregateVersion'=aggregate_version::text
        AND payload->>'movementId'=movement_id::text AND jsonb_typeof(payload->'schemaVersion')='number'
        AND jsonb_typeof(payload->'aggregateVersion')='number' AND jsonb_typeof(payload->'change')='object'
        AND jsonb_typeof(payload->'occurredAt')='string',FALSE)
        AND (payload->>'correlationId') IS NOT DISTINCT FROM correlation_id),
    CONSTRAINT inventory_outbox_aggregate_unique UNIQUE (org_id,store_id,position_id,aggregate_version),
    CONSTRAINT inventory_outbox_line_fk FOREIGN KEY (org_id,store_id,movement_id,position_id,aggregate_version)
        REFERENCES stockrescue.stock_movement_line(org_id,store_id,movement_id,position_id,position_version) ON DELETE RESTRICT
);
CREATE TABLE stockrescue.inventory_outbox_delivery (
    event_id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(next_attempt_at)),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ CHECK (published_at IS NULL OR isfinite(published_at)),
    last_error_code VARCHAR(64) CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT outbox_delivery_lease CHECK ((lease_owner IS NULL)=(lease_until IS NULL) AND (lease_until IS NULL OR isfinite(lease_until))),
    CONSTRAINT outbox_delivery_published CHECK (published_at IS NULL OR lease_owner IS NULL),
    CONSTRAINT outbox_delivery_event_fk FOREIGN KEY (org_id,store_id,event_id)
        REFERENCES stockrescue.inventory_outbox_event(org_id,store_id,id) ON DELETE RESTRICT
);
CREATE INDEX inventory_outbox_pending_idx ON stockrescue.inventory_outbox_delivery(next_attempt_at,event_id) WHERE published_at IS NULL;
CREATE FUNCTION stockrescue.guard_inventory_delivery() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.event_id,NEW.org_id,NEW.store_id) IS DISTINCT FROM (OLD.event_id,OLD.org_id,OLD.store_id)
        OR NEW.attempt_count<OLD.attempt_count OR (OLD.published_at IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Delivery identity, attempts and publication are monotonic' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inventory_delivery_guard BEFORE UPDATE ON stockrescue.inventory_outbox_delivery
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_inventory_delivery();

CREATE TABLE stockrescue.inventory_inbox (
    consumer_name VARCHAR(64) NOT NULL CHECK (consumer_name ~ '^[a-z][a-z0-9.-]{0,63}$'),
    event_id UUID NOT NULL,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    position_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version>0),
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(applied_at)),
    PRIMARY KEY (consumer_name,event_id),
    CONSTRAINT inventory_inbox_aggregate_unique UNIQUE (consumer_name,org_id,store_id,position_id,aggregate_version),
    CONSTRAINT inventory_inbox_scope UNIQUE (consumer_name,event_id,org_id,store_id,position_id,aggregate_version),
    CONSTRAINT inventory_inbox_position_fk FOREIGN KEY (org_id,store_id,position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT inventory_inbox_event_fk FOREIGN KEY (org_id,store_id,event_id,position_id,aggregate_version)
        REFERENCES stockrescue.inventory_outbox_event(org_id,store_id,id,position_id,aggregate_version) ON DELETE RESTRICT
);
CREATE TABLE stockrescue.inventory_change_feed (
    consumer_name VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    position_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    change JSONB NOT NULL CHECK (jsonb_typeof(change)='object'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    PRIMARY KEY (consumer_name,event_id),
    CONSTRAINT inventory_feed_inbox_fk FOREIGN KEY (consumer_name,event_id,org_id,store_id,position_id,aggregate_version)
        REFERENCES stockrescue.inventory_inbox(consumer_name,event_id,org_id,store_id,position_id,aggregate_version) ON DELETE RESTRICT
);
CREATE INDEX inventory_change_feed_page_idx ON stockrescue.inventory_change_feed(org_id,store_id,recorded_at,event_id);

CREATE FUNCTION stockrescue.check_inventory_command_complete() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM stockrescue.stock_movement m
        JOIN stockrescue.stock_movement_line l ON l.org_id=m.org_id AND l.store_id=m.store_id AND l.movement_id=m.id
        WHERE m.org_id=NEW.org_id AND m.store_id=NEW.store_id AND m.command_id=NEW.id) THEN
        RAISE EXCEPTION 'A command outcome requires its durable movement' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER inventory_command_complete AFTER INSERT ON stockrescue.inventory_command_receipt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION stockrescue.check_inventory_command_complete();

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_command_receipt','stock_movement','stock_movement_line','goods_receipt',
        'goods_receipt_line','stock_allocation_transition','inventory_reconciliation_run','inventory_reconciliation_result',
        'inventory_outbox_event','inventory_inbox','inventory_change_feed'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON stockrescue.%I FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation()',table_name||'_immutable',table_name);
        EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON stockrescue.%I FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation()',table_name||'_no_truncate',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['stock_position','stock_allocation','inventory_outbox_delivery'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE DELETE ON stockrescue.%I FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation()',table_name||'_no_delete',table_name);
        EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON stockrescue.%I FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation()',table_name||'_no_truncate',table_name);
    END LOOP;
END;
$$;

COMMENT ON TABLE stockrescue.stock_position IS 'Transactional projection; absent/uninitialized is not verified zero. Physical and bucket quantities reconstruct from the immutable movement lines.';
COMMENT ON TABLE stockrescue.stock_allocation IS 'Disjoint reservation or hold ownership. Current remainder has deferred immutable transition evidence; commands atomically post its corresponding bucket delta.';
COMMENT ON TABLE stockrescue.goods_receipt IS 'Physical delivery evidence with independent document deduplication; never a consignment/source observation or an opening balance import.';
COMMENT ON TABLE stockrescue.inventory_change_feed IS 'Derived read-only Kafka change feed. Its consumer never writes physical stock.';


-- Consolidated source: V013__inventory_reconciliation_recovery.sql
CREATE TABLE stockrescue.inventory_projection_repair (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    run_id UUID NOT NULL,
    position_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    prior_version BIGINT NOT NULL CHECK (prior_version>=0),
    repaired_version BIGINT NOT NULL CHECK (repaired_version=prior_version+1),
    on_hand BIGINT NOT NULL CHECK (on_hand>=0),
    reserved BIGINT NOT NULL CHECK (reserved>=0),
    quarantined BIGINT NOT NULL CHECK (quarantined>=0),
    other_blocked BIGINT NOT NULL CHECK (other_blocked>=0),
    trust_state VARCHAR(16) NOT NULL CHECK (trust_state='CLEAN'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    evidence_reference VARCHAR(256) NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference COLLATE "C" !~ '[[:cntrl:]]'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT inventory_projection_repair_buckets CHECK (reserved::numeric+quarantined+other_blocked<=on_hand),
    CONSTRAINT inventory_projection_repair_version UNIQUE (org_id,store_id,position_id,repaired_version),
    CONSTRAINT inventory_projection_repair_scope UNIQUE (org_id,store_id,id,position_id,repaired_version),
    CONSTRAINT inventory_projection_repair_run_fk FOREIGN KEY (org_id,store_id,run_id,position_id)
        REFERENCES stockrescue.inventory_reconciliation_result(org_id,store_id,run_id,position_id) ON DELETE RESTRICT,
    CONSTRAINT inventory_projection_repair_position_fk FOREIGN KEY (org_id,store_id,position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT inventory_projection_repair_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT
);
CREATE INDEX inventory_reconciliation_run_page_idx ON stockrescue.inventory_reconciliation_run(org_id,store_id,completed_at DESC,id DESC);
CREATE INDEX inventory_reconciliation_allocation_idx ON stockrescue.stock_allocation(org_id,store_id,position_id,id);
CREATE INDEX inventory_reconciliation_transition_idx ON stockrescue.stock_allocation_transition(org_id,store_id,position_id,allocation_id,version);

CREATE OR REPLACE FUNCTION stockrescue.guard_stock_position() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE repair stockrescue.inventory_projection_repair%ROWTYPE;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.version<>0 OR NEW.initialization_state<>'NOT_INITIALIZED' OR NEW.trust_state<>'UNVERIFIED'
            OR NEW.on_hand<>0 OR NEW.reserved<>0 OR NEW.quarantined<>0 OR NEW.other_blocked<>0 THEN
            RAISE EXCEPTION 'A new position requires uninitialized zero' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (NEW.id,NEW.org_id,NEW.store_id,NEW.location_id,NEW.product_id,NEW.lot_id,NEW.unit,NEW.created_at)
            IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.store_id,OLD.location_id,OLD.product_id,OLD.lot_id,OLD.unit,OLD.created_at)
            OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1
            OR (OLD.initialization_state='INITIALIZED' AND NEW.initialization_state<>'INITIALIZED') THEN
            RAISE EXCEPTION 'Position identity and monotonic version are required' USING ERRCODE='23514';
        END IF;
        SELECT r.* INTO repair FROM stockrescue.inventory_projection_repair r
        JOIN stockrescue.stock_movement_line l ON l.org_id=r.org_id AND l.store_id=r.store_id
            AND l.position_id=r.position_id AND l.position_version=r.repaired_version
        JOIN stockrescue.stock_movement m ON m.org_id=l.org_id AND m.store_id=l.store_id AND m.id=l.movement_id
        WHERE r.org_id=NEW.org_id AND r.store_id=NEW.store_id AND r.position_id=NEW.id
            AND r.prior_version=OLD.version AND r.repaired_version=NEW.version AND m.operation='REMEDIATE'
            AND r.on_hand=NEW.on_hand AND r.reserved=NEW.reserved AND r.quarantined=NEW.quarantined
            AND r.other_blocked=NEW.other_blocked AND r.trust_state=NEW.trust_state
            AND NEW.initialization_state='INITIALIZED'
            AND EXISTS (SELECT 1 FROM stockrescue.inventory_reconciliation_result rr
                WHERE rr.org_id=r.org_id AND rr.store_id=r.store_id AND rr.run_id=r.run_id
                  AND rr.position_id=r.position_id AND rr.severity='CRITICAL')
            AND r.on_hand=coalesce((SELECT sum(x.delta_on_hand::numeric) FROM stockrescue.stock_movement_line x
                WHERE x.org_id=r.org_id AND x.store_id=r.store_id AND x.position_id=r.position_id AND x.position_version<NEW.version),0)
            AND r.reserved=coalesce((SELECT sum(x.delta_reserved::numeric) FROM stockrescue.stock_movement_line x
                WHERE x.org_id=r.org_id AND x.store_id=r.store_id AND x.position_id=r.position_id AND x.position_version<NEW.version),0)
            AND r.quarantined=coalesce((SELECT sum(x.delta_quarantined::numeric) FROM stockrescue.stock_movement_line x
                WHERE x.org_id=r.org_id AND x.store_id=r.store_id AND x.position_id=r.position_id AND x.position_version<NEW.version),0)
            AND r.other_blocked=coalesce((SELECT sum(x.delta_other_blocked::numeric) FROM stockrescue.stock_movement_line x
                WHERE x.org_id=r.org_id AND x.store_id=r.store_id AND x.position_id=r.position_id AND x.position_version<NEW.version),0)
            AND r.reserved=coalesce((SELECT sum(a.remaining_quantity::numeric) FROM stockrescue.stock_allocation a
                WHERE a.org_id=r.org_id AND a.store_id=r.store_id AND a.position_id=r.position_id AND a.bucket='RESERVED' AND a.state='ACTIVE'),0)
            AND r.quarantined=coalesce((SELECT sum(a.remaining_quantity::numeric) FROM stockrescue.stock_allocation a
                WHERE a.org_id=r.org_id AND a.store_id=r.store_id AND a.position_id=r.position_id AND a.bucket='QUARANTINED' AND a.state='ACTIVE'),0)
            AND r.other_blocked=coalesce((SELECT sum(a.remaining_quantity::numeric) FROM stockrescue.stock_allocation a
                WHERE a.org_id=r.org_id AND a.store_id=r.store_id AND a.position_id=r.position_id AND a.bucket='OTHER_BLOCKED' AND a.state='ACTIVE'),0)
            AND l.delta_on_hand=0 AND l.delta_reserved=0 AND l.delta_quarantined=0 AND l.delta_other_blocked=0;
        IF OLD.trust_state='FROZEN' AND repair.id IS NULL THEN
            RAISE EXCEPTION 'A frozen position requires an audited projection repair' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM stockrescue.stock_movement_line l
            WHERE l.org_id=NEW.org_id AND l.store_id=NEW.store_id AND l.position_id=NEW.id AND l.position_version=NEW.version
              AND OLD.on_hand::numeric+l.delta_on_hand=NEW.on_hand
              AND OLD.reserved::numeric+l.delta_reserved=NEW.reserved
              AND OLD.quarantined::numeric+l.delta_quarantined=NEW.quarantined
              AND OLD.other_blocked::numeric+l.delta_other_blocked=NEW.other_blocked
              AND l.initialization_after=NEW.initialization_state AND l.trust_after=NEW.trust_state)
            AND repair.id IS NULL THEN
            RAISE EXCEPTION 'A projection update requires matching immutable movement evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER inventory_projection_repair_immutable BEFORE UPDATE OR DELETE ON stockrescue.inventory_projection_repair
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER inventory_projection_repair_no_truncate BEFORE TRUNCATE ON stockrescue.inventory_projection_repair
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();


-- Consolidated source: V014__inventory_kafka_delivery.sql
ALTER TABLE stockrescue.inventory_outbox_delivery
    ADD COLUMN claim_version BIGINT NOT NULL DEFAULT 0 CHECK (claim_version>=0),
    ADD COLUMN parked_at TIMESTAMPTZ CHECK (parked_at IS NULL OR isfinite(parked_at)),
    ADD COLUMN last_error_at TIMESTAMPTZ CHECK (last_error_at IS NULL OR isfinite(last_error_at)),
    ADD CONSTRAINT outbox_delivery_parked CHECK (parked_at IS NULL OR
        (published_at IS NULL AND lease_owner IS NULL AND last_error_code IS NOT NULL));

CREATE OR REPLACE FUNCTION stockrescue.guard_inventory_delivery() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE new_claim BOOLEAN; release_claim BOOLEAN; shorten_lease BOOLEAN;
BEGIN
    IF (NEW.event_id,NEW.org_id,NEW.store_id) IS DISTINCT FROM (OLD.event_id,OLD.org_id,OLD.store_id)
        OR (OLD.published_at IS NOT NULL AND NEW IS DISTINCT FROM OLD)
        OR (OLD.parked_at IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Delivery identity, attempts, fencing and terminal state are monotonic' USING ERRCODE='23514';
    END IF;
    new_claim := NEW.claim_version=OLD.claim_version+1 AND NEW.attempt_count=OLD.attempt_count+1
        AND NEW.lease_owner IS NOT NULL AND NEW.lease_until>clock_timestamp()
        AND NEW.lease_until<=clock_timestamp()+interval '5 minutes'
        AND (OLD.lease_owner IS NULL OR OLD.lease_until<=clock_timestamp())
        AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
        AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
        AND NEW.parked_at IS NOT DISTINCT FROM OLD.parked_at
        AND NEW.last_error_code IS NOT DISTINCT FROM OLD.last_error_code
        AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at;
    release_claim := NEW.claim_version=OLD.claim_version AND NEW.attempt_count=OLD.attempt_count
        AND OLD.lease_owner IS NOT NULL AND OLD.lease_until>clock_timestamp()
        AND NEW.lease_owner IS NULL AND NEW.lease_until IS NULL
        AND (
            (OLD.published_at IS NULL AND NEW.published_at IS NOT NULL AND NEW.parked_at IS NULL
                AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
                AND NEW.last_error_code IS NULL AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at)
            OR (OLD.parked_at IS NULL AND NEW.parked_at IS NOT NULL AND NEW.published_at IS NULL
                AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
                AND NEW.last_error_code IS NOT NULL AND NEW.last_error_at IS NOT NULL
                AND (OLD.last_error_at IS NULL OR NEW.last_error_at>=OLD.last_error_at))
            OR (NEW.published_at IS NULL AND NEW.parked_at IS NULL AND NEW.next_attempt_at>OLD.next_attempt_at
                AND NEW.last_error_code IS NOT NULL AND NEW.last_error_at IS NOT NULL
                AND (OLD.last_error_at IS NULL OR NEW.last_error_at>=OLD.last_error_at))
        );
    shorten_lease := NEW.claim_version=OLD.claim_version AND NEW.attempt_count=OLD.attempt_count
        AND OLD.lease_owner IS NOT NULL AND NEW.lease_owner=OLD.lease_owner
        AND NEW.lease_until IS NOT NULL AND NEW.lease_until<OLD.lease_until
        AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
        AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
        AND NEW.parked_at IS NOT DISTINCT FROM OLD.parked_at
        AND NEW.last_error_code IS NOT DISTINCT FROM OLD.last_error_code
        AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at;
    IF NEW.claim_version=OLD.claim_version+1 THEN
        IF NOT new_claim THEN
            RAISE EXCEPTION 'A delivery claim requires a fresh fencing version and lease' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.claim_version=OLD.claim_version THEN
        IF (NEW.attempt_count,NEW.lease_owner,NEW.lease_until,NEW.next_attempt_at,NEW.published_at,
                NEW.parked_at,NEW.last_error_code,NEW.last_error_at)
                IS DISTINCT FROM (OLD.attempt_count,OLD.lease_owner,OLD.lease_until,OLD.next_attempt_at,OLD.published_at,
                    OLD.parked_at,OLD.last_error_code,OLD.last_error_at)
            AND NOT release_claim AND NOT shorten_lease THEN
            RAISE EXCEPTION 'Only the current unexpired lease may publish, park or schedule a retry' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'A delivery fencing version advances by exactly one claim' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE INDEX inventory_outbox_claim_idx ON stockrescue.inventory_outbox_delivery(next_attempt_at,lease_until,event_id)
    WHERE published_at IS NULL AND parked_at IS NULL;

ALTER TABLE stockrescue.inventory_inbox
    ADD COLUMN processing_outcome VARCHAR(16) NOT NULL DEFAULT 'APPLIED'
        CHECK (processing_outcome IN ('APPLIED','STALE'));

CREATE TABLE stockrescue.inventory_change_projection (
    consumer_name VARCHAR(64) NOT NULL CHECK (consumer_name ~ '^[a-z][a-z0-9.-]{0,63}$'),
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    position_id UUID NOT NULL,
    event_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version>0),
    change JSONB NOT NULL CHECK (jsonb_typeof(change)='object'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(updated_at)),
    PRIMARY KEY (consumer_name,org_id,store_id,position_id),
    CONSTRAINT inventory_change_projection_event_fk FOREIGN KEY
        (consumer_name,event_id,org_id,store_id,position_id,aggregate_version)
        REFERENCES stockrescue.inventory_inbox(consumer_name,event_id,org_id,store_id,position_id,aggregate_version) ON DELETE RESTRICT,
    CONSTRAINT inventory_change_projection_position_fk FOREIGN KEY (org_id,store_id,position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT
);
CREATE INDEX inventory_change_projection_lag_idx ON stockrescue.inventory_change_projection(consumer_name,updated_at);

CREATE FUNCTION stockrescue.guard_inventory_change_projection() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.consumer_name,NEW.org_id,NEW.store_id,NEW.position_id) IS DISTINCT FROM
        (OLD.consumer_name,OLD.org_id,OLD.store_id,OLD.position_id) OR NEW.aggregate_version<=OLD.aggregate_version THEN
        RAISE EXCEPTION 'Derived inventory projections advance monotonically within their scope' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inventory_change_projection_guard BEFORE UPDATE ON stockrescue.inventory_change_projection
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_inventory_change_projection();
CREATE TRIGGER inventory_change_projection_no_delete BEFORE DELETE ON stockrescue.inventory_change_projection
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER inventory_change_projection_no_truncate BEFORE TRUNCATE ON stockrescue.inventory_change_projection
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE TABLE stockrescue.inventory_consumer_parked_event (
    consumer_name VARCHAR(64) NOT NULL CHECK (consumer_name ~ '^[a-z][a-z0-9.-]{0,63}$'),
    topic VARCHAR(249) NOT NULL CHECK (length(topic)>0),
    partition_id INTEGER NOT NULL CHECK (partition_id>=0),
    record_offset BIGINT NOT NULL CHECK (record_offset>=0),
    event_id UUID,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    failure_code VARCHAR(64) NOT NULL CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    occurrences INTEGER NOT NULL DEFAULT 1 CHECK (occurrences>0),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(first_seen_at)),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(last_seen_at) AND last_seen_at>=first_seen_at),
    PRIMARY KEY (consumer_name,topic,partition_id,record_offset)
);
CREATE INDEX inventory_consumer_parked_page_idx ON stockrescue.inventory_consumer_parked_event(consumer_name,last_seen_at DESC);
CREATE FUNCTION stockrescue.guard_inventory_parked_event() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.consumer_name,NEW.topic,NEW.partition_id,NEW.record_offset,NEW.event_id,NEW.payload_digest,NEW.failure_code,NEW.first_seen_at)
        IS DISTINCT FROM (OLD.consumer_name,OLD.topic,OLD.partition_id,OLD.record_offset,OLD.event_id,OLD.payload_digest,OLD.failure_code,OLD.first_seen_at)
        OR NEW.occurrences<OLD.occurrences OR NEW.last_seen_at<OLD.last_seen_at THEN
        RAISE EXCEPTION 'Parked event identity is immutable and observations are monotonic' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inventory_consumer_parked_guard BEFORE UPDATE ON stockrescue.inventory_consumer_parked_event
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_inventory_parked_event();
CREATE TRIGGER inventory_consumer_parked_no_delete BEFORE DELETE ON stockrescue.inventory_consumer_parked_event
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER inventory_consumer_parked_no_truncate BEFORE TRUNCATE ON stockrescue.inventory_consumer_parked_event
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

COMMENT ON TABLE stockrescue.inventory_change_projection IS 'Monotonic, derived consumer view. It is never an authoritative stock balance.';
COMMENT ON TABLE stockrescue.inventory_consumer_parked_event IS 'Payload-free durable record of malformed or unsupported topic records that were parked before the consumer offset advanced.';


-- Consolidated source: V015__inventory_outbox_redrive.sql
CREATE TABLE stockrescue.inventory_outbox_redrive_attempt (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    event_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation>0),
    request_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    request_fingerprint BYTEA NOT NULL CHECK (octet_length(request_fingerprint)=32),
    source_fingerprint BYTEA NOT NULL CHECK (octet_length(source_fingerprint)=32),
    source_claim_version BIGINT NOT NULL CHECK (source_claim_version>=0),
    source_attempt_count INTEGER NOT NULL CHECK (source_attempt_count>=0),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    evidence_reference VARCHAR(256) NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference COLLATE "C" !~ '[[:cntrl:]]'),
    accepted_state VARCHAR(24) NOT NULL CHECK (accepted_state IN ('REDRIVE_QUEUED','BLOCKED')),
    accepted_next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(accepted_next_attempt_at)),
    accepted_blocked_at TIMESTAMPTZ CHECK (accepted_blocked_at IS NULL OR isfinite(accepted_blocked_at)),
    accepted_error_code VARCHAR(64) CHECK (accepted_error_code IS NULL OR accepted_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(next_attempt_at)),
    lease_owner UUID,
    lease_until TIMESTAMPTZ CHECK (lease_until IS NULL OR isfinite(lease_until)),
    claim_version BIGINT NOT NULL DEFAULT 0 CHECK (claim_version>=0),
    published_at TIMESTAMPTZ CHECK (published_at IS NULL OR isfinite(published_at)),
    blocked_at TIMESTAMPTZ CHECK (blocked_at IS NULL OR isfinite(blocked_at)),
    last_error_code VARCHAR(64) CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    last_error_at TIMESTAMPTZ CHECK (last_error_at IS NULL OR isfinite(last_error_at)),
    CONSTRAINT inventory_outbox_redrive_generation_unique UNIQUE (event_id,generation),
    CONSTRAINT inventory_outbox_redrive_request_unique UNIQUE (org_id,actor_user_id,request_id),
    CONSTRAINT inventory_outbox_redrive_lease CHECK ((lease_owner IS NULL)=(lease_until IS NULL)),
    CONSTRAINT inventory_outbox_redrive_published CHECK (published_at IS NULL OR
        (lease_owner IS NULL AND blocked_at IS NULL)),
    CONSTRAINT inventory_outbox_redrive_blocked CHECK (blocked_at IS NULL OR
        (published_at IS NULL AND lease_owner IS NULL AND last_error_code IS NOT NULL AND last_error_at IS NOT NULL)),
    CONSTRAINT inventory_outbox_redrive_accepted_response CHECK (
        (accepted_state='REDRIVE_QUEUED' AND accepted_blocked_at IS NULL AND accepted_error_code IS NULL)
        OR (accepted_state='BLOCKED' AND accepted_blocked_at IS NOT NULL AND accepted_error_code IS NOT NULL)),
    CONSTRAINT inventory_outbox_redrive_event_fk FOREIGN KEY (org_id,store_id,event_id)
        REFERENCES stockrescue.inventory_outbox_event(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT inventory_outbox_redrive_delivery_fk FOREIGN KEY (event_id)
        REFERENCES stockrescue.inventory_outbox_delivery(event_id) ON DELETE RESTRICT,
    CONSTRAINT inventory_outbox_redrive_store_fk FOREIGN KEY (org_id,store_id)
        REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT inventory_outbox_redrive_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX inventory_outbox_redrive_one_active_idx
    ON stockrescue.inventory_outbox_redrive_attempt(event_id)
    WHERE published_at IS NULL AND blocked_at IS NULL;
CREATE INDEX inventory_outbox_redrive_claim_idx
    ON stockrescue.inventory_outbox_redrive_attempt(next_attempt_at,lease_until,event_id,generation)
    WHERE published_at IS NULL AND blocked_at IS NULL;
CREATE INDEX inventory_outbox_redrive_scope_idx
    ON stockrescue.inventory_outbox_redrive_attempt(org_id,store_id,event_id,generation DESC);

CREATE FUNCTION stockrescue.guard_inventory_outbox_redrive_attempt() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE original record; expected_generation BIGINT; new_claim BOOLEAN; release_claim BOOLEAN;
BEGIN
    IF TG_OP='INSERT' THEN
        SELECT d.* INTO original FROM stockrescue.inventory_outbox_delivery d
        WHERE d.event_id=NEW.event_id FOR UPDATE;
        IF NOT FOUND OR (original.org_id,original.store_id) IS DISTINCT FROM (NEW.org_id,NEW.store_id)
            OR original.parked_at IS NULL OR original.published_at IS NOT NULL
            OR original.claim_version<>NEW.source_claim_version OR original.attempt_count<>NEW.source_attempt_count THEN
            RAISE EXCEPTION 'A redrive must reference the current terminal parked delivery' USING ERRCODE='23514';
        END IF;
        IF EXISTS (SELECT 1 FROM stockrescue.inventory_outbox_redrive_attempt a
                WHERE a.event_id=NEW.event_id AND a.published_at IS NOT NULL)
            OR EXISTS (SELECT 1 FROM stockrescue.inventory_outbox_redrive_attempt a
                WHERE a.event_id=NEW.event_id AND a.published_at IS NULL AND a.blocked_at IS NULL) THEN
            RAISE EXCEPTION 'An acknowledged or active redrive cannot be reactivated' USING ERRCODE='23514';
        END IF;
        SELECT coalesce(max(a.generation),0)+1 INTO expected_generation
        FROM stockrescue.inventory_outbox_redrive_attempt a WHERE a.event_id=NEW.event_id;
        IF NEW.generation<>expected_generation OR NEW.attempt_count<>0 OR NEW.claim_version<>0
            OR NEW.lease_owner IS NOT NULL OR NEW.lease_until IS NOT NULL OR NEW.published_at IS NOT NULL
            OR NEW.next_attempt_at>clock_timestamp()+interval '5 minutes'
            OR (NEW.blocked_at IS NULL AND NEW.accepted_state<>'REDRIVE_QUEUED')
            OR (NEW.blocked_at IS NOT NULL AND NEW.accepted_state<>'BLOCKED')
            OR NEW.accepted_next_attempt_at IS DISTINCT FROM NEW.next_attempt_at
            OR NEW.accepted_blocked_at IS DISTINCT FROM NEW.blocked_at
            OR (NEW.blocked_at IS NULL AND NEW.accepted_error_code IS NOT NULL)
            OR (NEW.blocked_at IS NOT NULL AND NEW.accepted_error_code IS DISTINCT FROM NEW.last_error_code)
            OR (NEW.blocked_at IS NULL AND (NEW.last_error_code IS NOT NULL OR NEW.last_error_at IS NOT NULL))
            OR (NEW.blocked_at IS NOT NULL AND (NEW.last_error_code IS NULL OR NEW.last_error_at IS NULL)) THEN
            RAISE EXCEPTION 'A redrive generation must start queued or explicitly blocked' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    ELSIF TG_OP='UPDATE' THEN
        IF (NEW.id,NEW.org_id,NEW.store_id,NEW.event_id,NEW.generation,NEW.request_id,NEW.actor_user_id,NEW.membership_id,
                NEW.request_fingerprint,NEW.source_fingerprint,NEW.source_claim_version,NEW.source_attempt_count,
                NEW.reason,NEW.evidence_reference,NEW.accepted_state,NEW.accepted_next_attempt_at,
                NEW.accepted_blocked_at,NEW.accepted_error_code,NEW.recorded_at)
                IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.store_id,OLD.event_id,OLD.generation,OLD.request_id,OLD.actor_user_id,OLD.membership_id,
                    OLD.request_fingerprint,OLD.source_fingerprint,OLD.source_claim_version,OLD.source_attempt_count,
                    OLD.reason,OLD.evidence_reference,OLD.accepted_state,OLD.accepted_next_attempt_at,
                    OLD.accepted_blocked_at,OLD.accepted_error_code,OLD.recorded_at)
            OR ((OLD.published_at IS NOT NULL OR OLD.blocked_at IS NOT NULL) AND NEW IS DISTINCT FROM OLD) THEN
            RAISE EXCEPTION 'Redrive request evidence and terminal states are immutable' USING ERRCODE='23514';
        END IF;

        new_claim := OLD.published_at IS NULL AND OLD.blocked_at IS NULL
            AND NEW.claim_version=OLD.claim_version+1 AND NEW.attempt_count=OLD.attempt_count+1
            AND NEW.lease_owner IS NOT NULL AND NEW.lease_until>clock_timestamp()
            AND NEW.lease_until<=clock_timestamp()+interval '5 minutes'
            AND NEW.next_attempt_at<=clock_timestamp()
            AND (OLD.lease_owner IS NULL OR OLD.lease_until<=clock_timestamp())
            AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
            AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
            AND NEW.blocked_at IS NOT DISTINCT FROM OLD.blocked_at
            AND NEW.last_error_code IS NOT DISTINCT FROM OLD.last_error_code
            AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at;
        release_claim := OLD.published_at IS NULL AND OLD.blocked_at IS NULL
            AND NEW.claim_version=OLD.claim_version AND NEW.attempt_count=OLD.attempt_count
            AND OLD.lease_owner IS NOT NULL AND OLD.lease_until>clock_timestamp()
            AND NEW.lease_owner IS NULL AND NEW.lease_until IS NULL
            AND (
                (NEW.published_at IS NOT NULL AND NEW.blocked_at IS NULL
                    AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
                    AND NEW.last_error_code IS NULL AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at)
                OR (NEW.published_at IS NULL AND NEW.blocked_at IS NOT NULL
                    AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
                    AND NEW.last_error_code IS NOT NULL AND NEW.last_error_at IS NOT NULL
                    AND (OLD.last_error_at IS NULL OR NEW.last_error_at>=OLD.last_error_at))
                OR (NEW.published_at IS NULL AND NEW.blocked_at IS NULL
                    AND NEW.next_attempt_at>OLD.next_attempt_at
                    AND NEW.next_attempt_at<=clock_timestamp()+interval '5 minutes'
                    AND NEW.last_error_code IS NOT NULL AND NEW.last_error_at IS NOT NULL
                    AND (OLD.last_error_at IS NULL OR NEW.last_error_at>=OLD.last_error_at))
            );
        IF NEW.claim_version=OLD.claim_version+1 THEN
            IF NOT new_claim THEN
                RAISE EXCEPTION 'A redrive claim requires a fresh fencing version and bounded lease' USING ERRCODE='23514';
            END IF;
        ELSIF NEW.claim_version=OLD.claim_version THEN
            IF (NEW.attempt_count,NEW.lease_owner,NEW.lease_until,NEW.next_attempt_at,NEW.published_at,
                    NEW.blocked_at,NEW.last_error_code,NEW.last_error_at)
                    IS DISTINCT FROM (OLD.attempt_count,OLD.lease_owner,OLD.lease_until,OLD.next_attempt_at,OLD.published_at,
                        OLD.blocked_at,OLD.last_error_code,OLD.last_error_at)
                AND NOT release_claim THEN
                RAISE EXCEPTION 'Only the current unexpired redrive lease may acknowledge, block or schedule a retry' USING ERRCODE='23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'A redrive fencing version advances by exactly one claim' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    ELSE
        RAISE EXCEPTION 'Redrive generations cannot be deleted' USING ERRCODE='55000';
    END IF;
END;
$$;

CREATE TRIGGER inventory_outbox_redrive_guard
    BEFORE INSERT OR UPDATE OR DELETE ON stockrescue.inventory_outbox_redrive_attempt
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_inventory_outbox_redrive_attempt();
CREATE TRIGGER inventory_outbox_redrive_no_truncate
    BEFORE TRUNCATE ON stockrescue.inventory_outbox_redrive_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

COMMENT ON TABLE stockrescue.inventory_outbox_redrive_attempt IS
    'Append-only operator requests with fenced delivery attempts for the same immutable parked inventory event.';


-- Consolidated source: V016__durable_inventory_command_intents.sql
CREATE TABLE stockrescue.inventory_command_intent (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    operation VARCHAR(24) NOT NULL CHECK (operation IN
        ('RECEIPT_DOCUMENT','RESERVE','RELEASE','ADJUST','WRITE_OFF','QUARANTINE','HOLD','RELEASE_HOLD','SALE')),
    subject VARCHAR(48) NOT NULL CHECK (subject='receipt-document' OR subject ~ '^position:[0-9a-fA-F-]{36}$'),
    request_id UUID NOT NULL,
    payload_fingerprint BYTEA NOT NULL CHECK (octet_length(payload_fingerprint)=32),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='object' AND octet_length(payload::text)<=65536),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','ACKNOWLEDGED','REVIEWED')),
    prepared_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(prepared_at)),
    executed_at TIMESTAMPTZ CHECK (executed_at IS NULL OR isfinite(executed_at)),
    resolved_at TIMESTAMPTZ CHECK (resolved_at IS NULL OR isfinite(resolved_at)),
    CONSTRAINT inventory_command_intent_resolution CHECK
        ((state='PENDING' AND resolved_at IS NULL) OR (state<>'PENDING' AND resolved_at IS NOT NULL)),
    CONSTRAINT inventory_command_intent_execution_state CHECK
        ((state='REVIEWED' AND executed_at IS NULL) OR (state IN ('PENDING','ACKNOWLEDGED'))),
    CONSTRAINT inventory_command_intent_acknowledged_execution CHECK
        (state<>'ACKNOWLEDGED' OR executed_at IS NOT NULL),
    CONSTRAINT inventory_command_intent_request_unique UNIQUE (org_id,actor_user_id,operation,request_id),
    CONSTRAINT inventory_command_intent_scope UNIQUE (org_id,store_id,id),
    CONSTRAINT inventory_command_intent_store_fk FOREIGN KEY (org_id,store_id)
        REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT inventory_command_intent_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT,
    CONSTRAINT inventory_command_intent_operation_subject CHECK
        ((operation='RECEIPT_DOCUMENT' AND subject='receipt-document') OR
         (operation<>'RECEIPT_DOCUMENT' AND subject LIKE 'position:%'))
);

CREATE UNIQUE INDEX inventory_command_intent_active_subject_idx
    ON stockrescue.inventory_command_intent(org_id,store_id,actor_user_id,subject)
    WHERE state='PENDING';
CREATE INDEX inventory_command_intent_pending_actor_idx
    ON stockrescue.inventory_command_intent(org_id,actor_user_id,store_id,prepared_at,id)
    WHERE state='PENDING';
CREATE INDEX inventory_command_intent_retention_idx
    ON stockrescue.inventory_command_intent(resolved_at,id)
    WHERE state IN ('ACKNOWLEDGED','REVIEWED');

CREATE FUNCTION stockrescue.guard_inventory_command_intent() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE command_operation VARCHAR(24);
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.state='PENDING' OR OLD.resolved_at>clock_timestamp()-interval '90 days' THEN
            RAISE EXCEPTION 'Only resolved inventory intents past retention may be removed' USING ERRCODE='23514';
        END IF;
        RETURN OLD;
    END IF;
    IF (NEW.id,NEW.org_id,NEW.store_id,NEW.actor_user_id,NEW.membership_id,NEW.operation,NEW.subject,NEW.request_id,
            NEW.payload_fingerprint,NEW.payload,NEW.prepared_at)
        IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.store_id,OLD.actor_user_id,OLD.membership_id,OLD.operation,OLD.subject,OLD.request_id,
            OLD.payload_fingerprint,OLD.payload,OLD.prepared_at)
        OR OLD.state<>'PENDING' OR NEW.state NOT IN ('PENDING','ACKNOWLEDGED','REVIEWED')
        OR OLD.executed_at IS NOT NULL AND NEW.executed_at IS DISTINCT FROM OLD.executed_at
        OR OLD.executed_at IS NULL AND NEW.executed_at IS NOT NULL AND (NEW.state<>'PENDING' OR NEW.resolved_at IS NOT NULL)
        OR NEW.state='PENDING' AND NEW.resolved_at IS NOT NULL
        OR NEW.state<>'PENDING' AND NEW.resolved_at IS NULL
        OR NEW.state='ACKNOWLEDGED' AND NEW.executed_at IS NULL
        OR NEW.state='REVIEWED' AND NEW.executed_at IS NOT NULL THEN
        RAISE EXCEPTION 'Inventory intent content is immutable and can resolve only once' USING ERRCODE='23514';
    END IF;
    command_operation := CASE WHEN OLD.operation='RECEIPT_DOCUMENT' THEN 'RECEIVE' ELSE OLD.operation END;
    IF NEW.executed_at IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM stockrescue.inventory_command_receipt c
        WHERE c.org_id=OLD.org_id AND c.store_id=OLD.store_id AND c.actor_user_id=OLD.actor_user_id
          AND c.operation=command_operation AND c.request_id=OLD.request_id
    ) THEN
        RAISE EXCEPTION 'An inventory intent can be marked executed only after its command commits' USING ERRCODE='23514';
    END IF;
    IF NEW.state='ACKNOWLEDGED' AND NOT EXISTS (
        SELECT 1 FROM stockrescue.inventory_command_receipt c
        WHERE c.org_id=OLD.org_id AND c.store_id=OLD.store_id AND c.actor_user_id=OLD.actor_user_id
          AND c.operation=command_operation AND c.request_id=OLD.request_id
    ) THEN
        RAISE EXCEPTION 'A pending inventory intent requires a committed command before acknowledgement' USING ERRCODE='23514';
    END IF;
    IF NEW.state='REVIEWED' AND EXISTS (
        SELECT 1 FROM stockrescue.inventory_command_receipt c
        WHERE c.org_id=OLD.org_id AND c.store_id=OLD.store_id AND c.actor_user_id=OLD.actor_user_id
          AND c.operation=command_operation AND c.request_id=OLD.request_id
    ) THEN
        RAISE EXCEPTION 'A committed inventory intent cannot be dismissed as rejected' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER inventory_command_intent_guard
    BEFORE UPDATE OR DELETE ON stockrescue.inventory_command_intent
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_inventory_command_intent();

CREATE FUNCTION stockrescue.guard_reviewed_inventory_command() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM stockrescue.inventory_command_intent i
        WHERE i.org_id=NEW.org_id AND i.store_id=NEW.store_id AND i.actor_user_id=NEW.actor_user_id
          AND i.request_id=NEW.request_id
          AND CASE WHEN i.operation='RECEIPT_DOCUMENT' THEN 'RECEIVE' ELSE i.operation END=NEW.operation
          AND i.state='REVIEWED'
    ) THEN
        RAISE EXCEPTION 'A reviewed rejected inventory command cannot be executed' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER inventory_command_review_guard
    BEFORE INSERT ON stockrescue.inventory_command_receipt
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_reviewed_inventory_command();

CREATE FUNCTION stockrescue.assert_goods_receipt_movement(p_org UUID,p_store UUID,p_movement UUID) RETURNS void
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE movement_operation VARCHAR(24);
BEGIN
    SELECT m.operation INTO movement_operation FROM stockrescue.stock_movement m
    WHERE m.org_id=p_org AND m.store_id=p_store AND m.id=p_movement;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Receipt evidence requires its inventory movement' USING ERRCODE='23514';
    END IF;
    IF movement_operation<>'RECEIVE' THEN
        RETURN;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM stockrescue.goods_receipt r
        WHERE r.org_id=p_org AND r.store_id=p_store AND r.movement_id=p_movement
    ) THEN
        RAISE EXCEPTION 'A RECEIVE movement requires exactly one goods receipt header' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM stockrescue.stock_movement_line m
        WHERE m.org_id=p_org AND m.store_id=p_store AND m.movement_id=p_movement
    ) OR NOT EXISTS (
        SELECT 1 FROM stockrescue.goods_receipt_line r
        WHERE r.org_id=p_org AND r.store_id=p_store AND r.movement_id=p_movement
    ) THEN
        RAISE EXCEPTION 'A RECEIVE movement requires nonempty movement and supplier source lines' USING ERRCODE='23514';
    END IF;
    IF EXISTS (
        WITH movement_by_position AS (
            SELECT m.position_id,m.delta_on_hand::numeric AS delta_on_hand,
                m.delta_reserved::numeric AS delta_reserved,m.delta_quarantined::numeric AS delta_quarantined,
                m.delta_other_blocked::numeric AS delta_other_blocked
            FROM stockrescue.stock_movement_line m
            WHERE m.org_id=p_org AND m.store_id=p_store AND m.movement_id=p_movement
        ), receipt_by_position AS (
            SELECT l.position_id,sum(l.quantity) AS total_quantity,
                sum(l.quantity) FILTER (WHERE l.disposition='AVAILABLE') AS available_quantity,
                sum(l.quantity) FILTER (WHERE l.disposition='QUARANTINED') AS quarantined_quantity,
                sum(l.quantity) FILTER (WHERE l.disposition='OTHER_BLOCKED') AS other_blocked_quantity
            FROM stockrescue.goods_receipt_line l
            WHERE l.org_id=p_org AND l.store_id=p_store AND l.movement_id=p_movement
            GROUP BY l.position_id
        )
        SELECT 1 FROM movement_by_position m
        FULL OUTER JOIN receipt_by_position r USING (position_id)
        WHERE m.position_id IS NULL OR r.position_id IS NULL
          OR r.total_quantity<>m.delta_on_hand OR m.delta_reserved<>0
          OR COALESCE(r.available_quantity,0)<>m.delta_on_hand-m.delta_quarantined-m.delta_other_blocked
          OR COALESCE(r.quarantined_quantity,0)<>m.delta_quarantined
          OR COALESCE(r.other_blocked_quantity,0)<>m.delta_other_blocked
    ) THEN
        RAISE EXCEPTION 'Supplier lines and inventory movement quantities or dispositions do not reconcile' USING ERRCODE='23514';
    END IF;
END;
$$;

CREATE FUNCTION stockrescue.check_goods_receipt_movement() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE movement_id UUID;
BEGIN
    IF TG_TABLE_NAME='inventory_command_receipt' THEN
        IF NEW.operation<>'RECEIVE' THEN
            RETURN NULL;
        END IF;
        SELECT m.id INTO movement_id FROM stockrescue.stock_movement m
        WHERE m.org_id=NEW.org_id AND m.store_id=NEW.store_id AND m.command_id=NEW.id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'A receipt command requires its inventory movement' USING ERRCODE='23514';
        END IF;
        PERFORM stockrescue.assert_goods_receipt_movement(NEW.org_id,NEW.store_id,movement_id);
    ELSE
        PERFORM stockrescue.assert_goods_receipt_movement(NEW.org_id,NEW.store_id,NEW.movement_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER inventory_receipt_command_reconcile
    AFTER INSERT ON stockrescue.inventory_command_receipt DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION stockrescue.check_goods_receipt_movement();
CREATE CONSTRAINT TRIGGER inventory_receipt_movement_reconcile
    AFTER INSERT ON stockrescue.stock_movement_line DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION stockrescue.check_goods_receipt_movement();
CREATE CONSTRAINT TRIGGER inventory_receipt_header_reconcile
    AFTER INSERT ON stockrescue.goods_receipt DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION stockrescue.check_goods_receipt_movement();
CREATE CONSTRAINT TRIGGER inventory_receipt_line_reconcile
    AFTER INSERT ON stockrescue.goods_receipt_line DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION stockrescue.check_goods_receipt_movement();

DO $$
DECLARE movement RECORD;
BEGIN
    FOR movement IN SELECT org_id,store_id,id FROM stockrescue.stock_movement WHERE operation='RECEIVE' LOOP
        PERFORM stockrescue.assert_goods_receipt_movement(movement.org_id,movement.store_id,movement.id);
    END LOOP;
END;
$$;


-- Manual transfer custody foundation. The transfer account is a transit
-- projection; stock remains authoritative in stock_position and its ledger.
ALTER TABLE stockrescue.transport_lane
    ADD CONSTRAINT transport_lane_transfer_scope
        UNIQUE (org_id,id,origin_store_id,destination_store_id);

CREATE TABLE stockrescue.transfer_order (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    source_store_id UUID NOT NULL,
    destination_store_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    created_by_membership_id UUID NOT NULL,
    approved_lane_id UUID,
    approved_lane_revision_id UUID,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED','REJECTED','CANCELLED','APPROVED','PICKING','PICKED',
            'IN_TRANSIT','PARTIALLY_RECEIVED','RECONCILIATION_REQUIRED','RECEIVED','CLOSED_WITH_DISCREPANCY')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(updated_at) AND updated_at>=created_at),
    CONSTRAINT transfer_order_distinct_stores CHECK (source_store_id<>destination_store_id),
    CONSTRAINT transfer_order_lane_pair CHECK ((approved_lane_id IS NULL)=(approved_lane_revision_id IS NULL)),
    CONSTRAINT transfer_order_scope UNIQUE (org_id,id),
    CONSTRAINT transfer_order_store_scope UNIQUE (org_id,id,source_store_id,destination_store_id),
    CONSTRAINT transfer_order_source_store_fk FOREIGN KEY (org_id,source_store_id)
        REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_order_destination_store_fk FOREIGN KEY (org_id,destination_store_id)
        REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_order_creator_fk FOREIGN KEY (org_id,created_by_membership_id,created_by_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT,
    CONSTRAINT transfer_order_lane_fk FOREIGN KEY (org_id,approved_lane_id,source_store_id,destination_store_id)
        REFERENCES stockrescue.transport_lane(org_id,id,origin_store_id,destination_store_id) ON DELETE RESTRICT,
    CONSTRAINT transfer_order_lane_revision_fk FOREIGN KEY (org_id,approved_lane_id,approved_lane_revision_id)
        REFERENCES stockrescue.lane_revision(org_id,lane_id,id) ON DELETE RESTRICT
);
CREATE INDEX transfer_order_source_queue_idx
    ON stockrescue.transfer_order(org_id,source_store_id,status,updated_at DESC,id DESC);
CREATE INDEX transfer_order_destination_queue_idx
    ON stockrescue.transfer_order(org_id,destination_store_id,status,updated_at DESC,id DESC);

CREATE TABLE stockrescue.transfer_command_receipt (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    operation VARCHAR(32) NOT NULL CHECK (operation IN
        ('CREATE','APPROVE','REJECT','CANCEL','START_PICK','PICK','DISPATCH','RECEIVE','OPEN_DISCREPANCY',
         'CONFIRM_LOSS','REQUEST_RETURN','CONFIRM_RETURN','RESUME','CLOSE','RECONCILE')),
    request_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    outcome JSONB NOT NULL CHECK (jsonb_typeof(outcome)='object'),
    correlation_id VARCHAR(64) CHECK (correlation_id IS NULL OR correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    stock_store_id UUID,
    stock_command_receipt_id UUID,
    stock_operation VARCHAR(24),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT transfer_command_request_unique UNIQUE (org_id,actor_user_id,operation,request_id),
    CONSTRAINT transfer_command_scope UNIQUE (org_id,transfer_id,id),
    CONSTRAINT transfer_command_actor_scope UNIQUE (org_id,transfer_id,id,actor_user_id,membership_id),
    CONSTRAINT transfer_command_stock_link CHECK (
        (stock_store_id IS NULL)=(stock_command_receipt_id IS NULL)
        AND (stock_store_id IS NULL)=(stock_operation IS NULL)),
    CONSTRAINT transfer_command_transfer_fk FOREIGN KEY (org_id,transfer_id)
        REFERENCES stockrescue.transfer_order(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_command_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT,
    CONSTRAINT transfer_command_stock_fk FOREIGN KEY (org_id,stock_store_id,stock_command_receipt_id,stock_operation)
        REFERENCES stockrescue.inventory_command_receipt(org_id,store_id,id,operation) ON DELETE RESTRICT
);
CREATE INDEX transfer_command_transfer_idx
    ON stockrescue.transfer_command_receipt(org_id,transfer_id,recorded_at,id);

CREATE TABLE stockrescue.transfer_transition (
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version>=0),
    previous_version BIGINT GENERATED ALWAYS AS (CASE WHEN version>0 THEN version-1 ELSE NULL END) STORED,
    status_before VARCHAR(32),
    status_after VARCHAR(32) NOT NULL CHECK (status_after IN
        ('REQUESTED','REJECTED','CANCELLED','APPROVED','PICKING','PICKED','IN_TRANSIT',
         'PARTIALLY_RECEIVED','RECONCILIATION_REQUIRED','RECEIVED','CLOSED_WITH_DISCREPANCY')),
    command_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    evidence_reference VARCHAR(256) NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference COLLATE "C" !~ '[[:cntrl:]]'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    PRIMARY KEY (org_id,transfer_id,version),
    CONSTRAINT transfer_transition_result UNIQUE (org_id,transfer_id,version,status_after),
    CONSTRAINT transfer_transition_command_unique UNIQUE (org_id,transfer_id,command_id),
    CONSTRAINT transfer_transition_initial CHECK (
        (version=0 AND previous_version IS NULL AND status_before IS NULL AND status_after='REQUESTED')
        OR (version>0 AND previous_version=version-1 AND status_before IS NOT NULL)),
    CONSTRAINT transfer_transition_transfer_fk FOREIGN KEY (org_id,transfer_id)
        REFERENCES stockrescue.transfer_order(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_transition_command_fk FOREIGN KEY (org_id,transfer_id,command_id)
        REFERENCES stockrescue.transfer_command_receipt(org_id,transfer_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_transition_actor_fk FOREIGN KEY (org_id,transfer_id,command_id,actor_user_id,membership_id)
        REFERENCES stockrescue.transfer_command_receipt(org_id,transfer_id,id,actor_user_id,membership_id) ON DELETE RESTRICT,
    CONSTRAINT transfer_transition_previous_fk FOREIGN KEY (org_id,transfer_id,previous_version,status_before)
        REFERENCES stockrescue.transfer_transition(org_id,transfer_id,version,status_after) ON DELETE RESTRICT
);
CREATE INDEX transfer_transition_timeline_idx
    ON stockrescue.transfer_transition(org_id,transfer_id,recorded_at DESC,version DESC);

CREATE FUNCTION stockrescue.validate_transfer_transition_command() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE command_operation VARCHAR(32); expected_operation VARCHAR(32);
BEGIN
    expected_operation:=CASE
        WHEN NEW.status_before IS NULL AND NEW.status_after='REQUESTED' THEN 'CREATE'
        WHEN NEW.status_after='APPROVED' THEN 'APPROVE'
        WHEN NEW.status_after='REJECTED' THEN 'REJECT'
        WHEN NEW.status_after='CANCELLED' THEN 'CANCEL'
        WHEN NEW.status_after='PICKING' AND NEW.status_before='APPROVED' THEN 'START_PICK'
        WHEN NEW.status_after='PICKING' THEN 'PICK'
        WHEN NEW.status_after='PICKED' THEN 'PICK'
        WHEN NEW.status_after='IN_TRANSIT' AND NEW.status_before='PICKED' THEN 'DISPATCH'
        WHEN NEW.status_after='IN_TRANSIT' AND NEW.status_before='RECONCILIATION_REQUIRED' THEN 'RESUME'
        WHEN NEW.status_after IN ('PARTIALLY_RECEIVED','RECEIVED') THEN 'RECEIVE'
        WHEN NEW.status_after='RECONCILIATION_REQUIRED' THEN NULL
        WHEN NEW.status_after='CLOSED_WITH_DISCREPANCY' THEN 'CLOSE'
        ELSE NULL
    END;
    SELECT operation INTO command_operation FROM stockrescue.transfer_command_receipt
     WHERE org_id=NEW.org_id AND transfer_id=NEW.transfer_id AND id=NEW.command_id;
    IF NOT FOUND OR (NEW.status_after='RECONCILIATION_REQUIRED'
            AND (command_operation IS NULL OR command_operation NOT IN
                ('OPEN_DISCREPANCY','CONFIRM_LOSS','REQUEST_RETURN','CONFIRM_RETURN','RECEIVE')))
        OR (NEW.status_after<>'RECONCILIATION_REQUIRED' AND
            (expected_operation IS NULL OR command_operation<>expected_operation)) THEN
        RAISE EXCEPTION 'Transfer transition must match its typed command' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfer_transition_command_validate BEFORE INSERT ON stockrescue.transfer_transition
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_transfer_transition_command();

CREATE TABLE stockrescue.transfer_line (
    id UUID NOT NULL,
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    source_store_id UUID NOT NULL,
    destination_store_id UUID NOT NULL,
    line_number INTEGER NOT NULL CHECK (line_number>0),
    source_position_id UUID NOT NULL,
    destination_location_id UUID NOT NULL,
    requested_quantity BIGINT NOT NULL CHECK (requested_quantity>0),
    unit VARCHAR(8) NOT NULL DEFAULT 'EA' CHECK (unit='EA'),
    expected_source_version BIGINT NOT NULL CHECK (expected_source_version>=0 AND expected_source_version<9223372036854775807),
    reservation_allocation_id UUID,
    reservation_bucket VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(created_at)),
    PRIMARY KEY (org_id,transfer_id,id),
    CONSTRAINT transfer_line_order_scope UNIQUE (org_id,transfer_id,id,source_store_id,destination_store_id),
    CONSTRAINT transfer_line_number_unique UNIQUE (org_id,transfer_id,line_number),
    CONSTRAINT transfer_line_source_position_unique UNIQUE (org_id,transfer_id,source_position_id),
    CONSTRAINT transfer_line_reservation_pair CHECK (
        (reservation_allocation_id IS NULL)=(reservation_bucket IS NULL)
        AND (reservation_bucket IS NULL OR reservation_bucket='RESERVED')),
    CONSTRAINT transfer_line_transfer_fk FOREIGN KEY (org_id,transfer_id,source_store_id,destination_store_id)
        REFERENCES stockrescue.transfer_order(org_id,id,source_store_id,destination_store_id) ON DELETE RESTRICT,
    CONSTRAINT transfer_line_source_position_fk FOREIGN KEY (org_id,source_store_id,source_position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_line_destination_location_fk FOREIGN KEY (org_id,destination_store_id,destination_location_id)
        REFERENCES stockrescue.storage_location(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_line_reservation_fk FOREIGN KEY
        (org_id,source_store_id,reservation_allocation_id,source_position_id,reservation_bucket)
        REFERENCES stockrescue.stock_allocation(org_id,store_id,id,position_id,bucket) ON DELETE RESTRICT
);
CREATE INDEX transfer_line_source_position_idx
    ON stockrescue.transfer_line(org_id,source_store_id,source_position_id);
CREATE INDEX transfer_line_destination_location_idx
    ON stockrescue.transfer_line(org_id,destination_store_id,destination_location_id);
CREATE UNIQUE INDEX transfer_line_reservation_owner_unique
    ON stockrescue.transfer_line(org_id,source_store_id,reservation_allocation_id)
    WHERE reservation_allocation_id IS NOT NULL;

CREATE TABLE stockrescue.transfer_physical_document (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    source_store_id UUID NOT NULL,
    destination_store_id UUID NOT NULL,
    document_store_id UUID NOT NULL,
    command_id UUID NOT NULL,
    store_side VARCHAR(16) NOT NULL CHECK (store_side IN ('SOURCE','DESTINATION')),
    document_type VARCHAR(16) NOT NULL CHECK (document_type IN ('PICK','DISPATCH','RECEIPT','DAMAGE','RETURN','LOSS')),
    document_reference VARCHAR(200) COLLATE "C" NOT NULL
        CHECK (document_reference=btrim(document_reference) AND length(document_reference)>0 AND document_reference !~ '[[:cntrl:]]'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    evidence_reference VARCHAR(256) NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference COLLATE "C" !~ '[[:cntrl:]]'),
    recorded_by_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT transfer_document_store_side CHECK (
        (store_side='SOURCE' AND document_store_id=source_store_id)
        OR (store_side='DESTINATION' AND document_store_id=destination_store_id)),
    CONSTRAINT transfer_document_identity UNIQUE (org_id,document_store_id,document_type,document_reference),
    CONSTRAINT transfer_document_scope UNIQUE (org_id,transfer_id,id),
    CONSTRAINT transfer_document_type_scope UNIQUE (org_id,transfer_id,id,document_type),
    CONSTRAINT transfer_document_transfer_fk FOREIGN KEY (org_id,transfer_id,source_store_id,destination_store_id)
        REFERENCES stockrescue.transfer_order(org_id,id,source_store_id,destination_store_id) ON DELETE RESTRICT,
    CONSTRAINT transfer_document_store_fk FOREIGN KEY (org_id,document_store_id)
        REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_document_actor_fk FOREIGN KEY (org_id,membership_id,recorded_by_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT,
    CONSTRAINT transfer_document_command_fk FOREIGN KEY (org_id,transfer_id,command_id)
        REFERENCES stockrescue.transfer_command_receipt(org_id,transfer_id,id) ON DELETE RESTRICT
);
CREATE INDEX transfer_document_timeline_idx
    ON stockrescue.transfer_physical_document(org_id,transfer_id,recorded_at DESC,id DESC);

CREATE TABLE stockrescue.transfer_physical_document_line (
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    document_id UUID NOT NULL,
    line_id UUID NOT NULL,
    document_type VARCHAR(16) NOT NULL CHECK (document_type IN ('PICK','DISPATCH','RECEIPT','DAMAGE','RETURN','LOSS')),
    line_number INTEGER NOT NULL CHECK (line_number>0),
    document_line_reference VARCHAR(64) COLLATE "C" NOT NULL
        CHECK (document_line_reference=btrim(document_line_reference) AND length(document_line_reference)>0 AND document_line_reference !~ '[[:cntrl:]]'),
    quantity BIGINT NOT NULL CHECK (quantity>0),
    disposition VARCHAR(16) NOT NULL CHECK (disposition IN ('PICKED','AVAILABLE','QUARANTINED','DAMAGED','RETURNED','LOST','COUNTED')),
    observed_sku VARCHAR(64) COLLATE "C",
    observed_lot_code VARCHAR(128) COLLATE "C",
    source_position_version BIGINT CHECK (source_position_version>0),
    PRIMARY KEY (org_id,document_id,line_number),
    CONSTRAINT transfer_document_line_reference_unique UNIQUE (org_id,document_id,document_line_reference),
    CONSTRAINT transfer_document_line_header_fk FOREIGN KEY (org_id,transfer_id,document_id,document_type)
        REFERENCES stockrescue.transfer_physical_document(org_id,transfer_id,id,document_type) ON DELETE RESTRICT,
    CONSTRAINT transfer_document_line_transfer_line_fk FOREIGN KEY (org_id,transfer_id,line_id)
        REFERENCES stockrescue.transfer_line(org_id,transfer_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_document_line_evidence CHECK (
        (document_type='PICK' AND disposition='PICKED' AND source_position_version IS NOT NULL
            AND observed_sku IS NOT NULL AND observed_lot_code IS NOT NULL)
        OR (document_type='DISPATCH' AND disposition='COUNTED' AND source_position_version IS NULL
            AND observed_sku IS NOT NULL AND observed_lot_code IS NOT NULL)
        OR (document_type='RECEIPT' AND disposition IN ('AVAILABLE','QUARANTINED') AND source_position_version IS NULL
            AND observed_sku IS NOT NULL AND observed_lot_code IS NOT NULL)
        OR (document_type='DAMAGE' AND disposition='DAMAGED' AND source_position_version IS NULL
            AND observed_sku IS NOT NULL AND observed_lot_code IS NOT NULL)
        OR (document_type='LOSS' AND disposition='LOST' AND source_position_version IS NULL
            AND observed_sku IS NOT NULL AND observed_lot_code IS NOT NULL)
        OR (document_type='RETURN' AND disposition='RETURNED' AND source_position_version IS NULL
            AND observed_sku IS NOT NULL AND observed_lot_code IS NOT NULL))
);
CREATE INDEX transfer_document_line_transfer_idx
    ON stockrescue.transfer_physical_document_line(org_id,transfer_id,line_id,document_id);
CREATE UNIQUE INDEX transfer_pick_line_once_idx
    ON stockrescue.transfer_physical_document_line(org_id,transfer_id,line_id) WHERE document_type='PICK';
CREATE UNIQUE INDEX transfer_dispatch_document_once_idx
    ON stockrescue.transfer_physical_document(org_id,transfer_id) WHERE document_type='DISPATCH';

CREATE FUNCTION stockrescue.validate_transfer_document_line() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE document_row RECORD; transfer_line_row RECORD; source_row RECORD; expected_source_version BIGINT;
BEGIN
    SELECT document_type,source_store_id INTO document_row FROM stockrescue.transfer_physical_document
     WHERE org_id=NEW.org_id AND transfer_id=NEW.transfer_id AND id=NEW.document_id;
    SELECT source_position_id,requested_quantity INTO transfer_line_row FROM stockrescue.transfer_line
     WHERE org_id=NEW.org_id AND transfer_id=NEW.transfer_id AND id=NEW.line_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Physical evidence requires its transfer line' USING ERRCODE='23503';
    END IF;
    IF NEW.document_type IN ('PICK','DISPATCH') THEN
        IF NEW.quantity<>transfer_line_row.requested_quantity THEN
            RAISE EXCEPTION 'Picking and dispatch evidence must match the complete requested line' USING ERRCODE='23514';
        END IF;
        SELECT sp.version,p.sku,lot.lot_code INTO source_row
          FROM stockrescue.stock_position sp
          JOIN stockrescue.product p ON p.org_id=sp.org_id AND p.id=sp.product_id
          JOIN stockrescue.manufacturer_lot lot ON lot.org_id=sp.org_id AND lot.product_id=sp.product_id AND lot.id=sp.lot_id
         WHERE sp.org_id=NEW.org_id AND sp.store_id=document_row.source_store_id
           AND sp.id=transfer_line_row.source_position_id FOR SHARE OF sp;
        IF NOT FOUND OR NEW.observed_sku IS DISTINCT FROM source_row.sku
            OR NEW.observed_lot_code IS DISTINCT FROM source_row.lot_code THEN
            RAISE EXCEPTION 'Physical evidence scan must match the linked source product and lot' USING ERRCODE='23514';
        END IF;
        expected_source_version:=source_row.version;
        IF NEW.document_type='DISPATCH' THEN expected_source_version:=source_row.version-1; END IF;
        IF NEW.document_type='PICK' AND NEW.source_position_version<>source_row.version THEN
            RAISE EXCEPTION 'Pick evidence must capture the current source position version' USING ERRCODE='23514';
        END IF;
        IF NEW.document_type='DISPATCH' AND NOT EXISTS (
            SELECT 1 FROM stockrescue.transfer_physical_document d
            JOIN stockrescue.transfer_physical_document_line pick
              ON pick.org_id=d.org_id AND pick.transfer_id=d.transfer_id AND pick.document_id=d.id
                AND pick.document_type=d.document_type
            WHERE d.org_id=NEW.org_id AND d.transfer_id=NEW.transfer_id AND d.document_type='PICK'
              AND pick.line_id=NEW.line_id AND pick.quantity=NEW.quantity
              AND pick.observed_sku=NEW.observed_sku AND pick.observed_lot_code=NEW.observed_lot_code
              AND pick.source_position_version=expected_source_version
        ) THEN
            RAISE EXCEPTION 'Dispatch requires a current matching physical pick record' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfer_document_line_validate BEFORE INSERT ON stockrescue.transfer_physical_document_line
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_transfer_document_line();

CREATE FUNCTION stockrescue.validate_transfer_document_command() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE command_operation VARCHAR(32); expected_operation VARCHAR(32); command_actor UUID; command_membership UUID;
BEGIN
    expected_operation:=CASE NEW.document_type
        WHEN 'PICK' THEN 'PICK'
        WHEN 'DISPATCH' THEN 'DISPATCH'
        WHEN 'RECEIPT' THEN 'RECEIVE'
        WHEN 'DAMAGE' THEN 'CONFIRM_LOSS'
        WHEN 'LOSS' THEN 'CONFIRM_LOSS'
        WHEN 'RETURN' THEN 'CONFIRM_RETURN'
    END;
    SELECT operation,actor_user_id,membership_id INTO command_operation,command_actor,command_membership
      FROM stockrescue.transfer_command_receipt
     WHERE org_id=NEW.org_id AND transfer_id=NEW.transfer_id AND id=NEW.command_id;
    IF NOT FOUND OR command_operation IS DISTINCT FROM expected_operation
        OR command_actor<>NEW.recorded_by_user_id OR command_membership<>NEW.membership_id THEN
        RAISE EXCEPTION 'Physical document must match its transfer command and actor' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfer_document_command_validate BEFORE INSERT ON stockrescue.transfer_physical_document
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_transfer_document_command();

CREATE TABLE stockrescue.transfer_reconciliation_evidence (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    line_id UUID,
    command_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    evidence_type VARCHAR(24) NOT NULL CHECK (evidence_type IN
        ('DISCREPANCY','RETURN_REQUEST','PROJECTION_REPAIR','CUSTODY_RECONCILIATION')),
    expected_state JSONB NOT NULL CHECK (jsonb_typeof(expected_state)='object'),
    observed_state JSONB NOT NULL CHECK (jsonb_typeof(observed_state)='object'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    evidence_reference VARCHAR(256) NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference COLLATE "C" !~ '[[:cntrl:]]'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT transfer_reconciliation_transfer_fk FOREIGN KEY (org_id,transfer_id)
        REFERENCES stockrescue.transfer_order(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_reconciliation_line_fk FOREIGN KEY (org_id,transfer_id,line_id)
        REFERENCES stockrescue.transfer_line(org_id,transfer_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_reconciliation_command_fk FOREIGN KEY (org_id,transfer_id,command_id)
        REFERENCES stockrescue.transfer_command_receipt(org_id,transfer_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_reconciliation_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT
);
CREATE INDEX transfer_reconciliation_timeline_idx
    ON stockrescue.transfer_reconciliation_evidence(org_id,transfer_id,recorded_at DESC,id DESC);
CREATE INDEX transfer_reconciliation_open_idx
    ON stockrescue.transfer_reconciliation_evidence(org_id,transfer_id,evidence_type,recorded_at DESC)
    WHERE evidence_type='DISCREPANCY';

CREATE TABLE stockrescue.transfer_custody_account (
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    line_id UUID NOT NULL,
    dispatched BIGINT NOT NULL DEFAULT 0 CHECK (dispatched>=0),
    physically_received BIGINT NOT NULL DEFAULT 0 CHECK (physically_received>=0),
    confirmed_loss BIGINT NOT NULL DEFAULT 0 CHECK (confirmed_loss>=0),
    physically_returned BIGINT NOT NULL DEFAULT 0 CHECK (physically_returned>=0),
    outstanding BIGINT NOT NULL DEFAULT 0 CHECK (outstanding>=0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(updated_at)),
    PRIMARY KEY (org_id,transfer_id,line_id),
    CONSTRAINT transfer_custody_account_conservation CHECK (
        dispatched::numeric=physically_received::numeric+confirmed_loss::numeric+physically_returned::numeric+outstanding::numeric),
    CONSTRAINT transfer_custody_account_initial CHECK (
        version>0 OR (dispatched=0 AND physically_received=0 AND confirmed_loss=0 AND physically_returned=0 AND outstanding=0)),
    CONSTRAINT transfer_custody_account_line_fk FOREIGN KEY (org_id,transfer_id,line_id)
        REFERENCES stockrescue.transfer_line(org_id,transfer_id,id) ON DELETE RESTRICT
);
CREATE INDEX transfer_custody_open_idx
    ON stockrescue.transfer_custody_account(org_id,transfer_id,line_id) WHERE outstanding>0;

CREATE TABLE stockrescue.transfer_custody_movement (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    line_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    previous_version BIGINT GENERATED ALWAYS AS (CASE WHEN version>1 THEN version-1 ELSE NULL END) STORED,
    movement_type VARCHAR(16) NOT NULL CHECK (movement_type IN ('DISPATCH','RECEIPT','LOSS','RETURN')),
    command_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity>0),
    outstanding_before BIGINT NOT NULL CHECK (outstanding_before>=0),
    outstanding_after BIGINT NOT NULL CHECK (outstanding_after>=0),
    stock_store_id UUID,
    stock_movement_id UUID,
    stock_position_id UUID,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    evidence_reference VARCHAR(256) NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference COLLATE "C" !~ '[[:cntrl:]]'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT transfer_custody_line_version_unique UNIQUE (org_id,transfer_id,line_id,version),
    CONSTRAINT transfer_custody_result_unique UNIQUE (org_id,transfer_id,line_id,version,outstanding_after),
    CONSTRAINT transfer_custody_command_line_unique UNIQUE (org_id,transfer_id,line_id,command_id,movement_type),
    CONSTRAINT transfer_custody_sequence CHECK ((version=1)=(movement_type='DISPATCH')),
    CONSTRAINT transfer_custody_outstanding CHECK (
        (movement_type='DISPATCH' AND outstanding_before=0 AND outstanding_after::numeric=outstanding_before::numeric+quantity::numeric)
        OR (movement_type<>'DISPATCH' AND quantity<=outstanding_before
            AND outstanding_after::numeric=outstanding_before::numeric-quantity::numeric)),
    CONSTRAINT transfer_custody_stock_link CHECK (
        (stock_store_id IS NULL)=(stock_movement_id IS NULL)
        AND (stock_store_id IS NULL)=(stock_position_id IS NULL)
        AND ((movement_type='LOSS')=(stock_movement_id IS NULL))),
    CONSTRAINT transfer_custody_transfer_line_fk FOREIGN KEY (org_id,transfer_id,line_id)
        REFERENCES stockrescue.transfer_line(org_id,transfer_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_custody_command_fk FOREIGN KEY (org_id,transfer_id,command_id)
        REFERENCES stockrescue.transfer_command_receipt(org_id,transfer_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_custody_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT,
    CONSTRAINT transfer_custody_previous_fk FOREIGN KEY (org_id,transfer_id,line_id,previous_version,outstanding_before)
        REFERENCES stockrescue.transfer_custody_movement(org_id,transfer_id,line_id,version,outstanding_after) ON DELETE RESTRICT,
    CONSTRAINT transfer_custody_stock_line_fk FOREIGN KEY (org_id,stock_store_id,stock_movement_id,stock_position_id)
        REFERENCES stockrescue.stock_movement_line(org_id,store_id,movement_id,position_id) ON DELETE RESTRICT
);
CREATE INDEX transfer_custody_timeline_idx
    ON stockrescue.transfer_custody_movement(org_id,transfer_id,recorded_at DESC,id DESC);
CREATE INDEX transfer_custody_line_timeline_idx
    ON stockrescue.transfer_custody_movement(org_id,transfer_id,line_id,version);
CREATE UNIQUE INDEX transfer_custody_single_dispatch_idx
    ON stockrescue.transfer_custody_movement(org_id,transfer_id,line_id) WHERE movement_type='DISPATCH';

CREATE FUNCTION stockrescue.guard_transfer_order() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.id,NEW.org_id,NEW.source_store_id,NEW.destination_store_id,NEW.created_by_user_id,
        NEW.created_by_membership_id,NEW.created_at)
        IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.source_store_id,OLD.destination_store_id,OLD.created_by_user_id,
        OLD.created_by_membership_id,OLD.created_at)
        OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1
        OR NEW.status=OLD.status AND OLD.status NOT IN ('PICKING','PARTIALLY_RECEIVED','RECONCILIATION_REQUIRED')
        OR NEW.updated_at<OLD.updated_at
        OR OLD.approved_lane_id IS NOT NULL
            AND (NEW.approved_lane_id,NEW.approved_lane_revision_id)
                IS DISTINCT FROM (OLD.approved_lane_id,OLD.approved_lane_revision_id) THEN
        RAISE EXCEPTION 'Transfer update must advance one status transition and version; identity and approved route snapshot are immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.status<>OLD.status AND NOT (
        (OLD.status='REQUESTED' AND NEW.status IN ('APPROVED','REJECTED','CANCELLED'))
        OR (OLD.status='APPROVED' AND NEW.status IN ('PICKING','CANCELLED'))
        OR (OLD.status='PICKING' AND NEW.status IN ('PICKING','PICKED','CANCELLED'))
        OR (OLD.status='PICKED' AND NEW.status IN ('IN_TRANSIT','CANCELLED'))
        OR (OLD.status='IN_TRANSIT' AND NEW.status IN ('PARTIALLY_RECEIVED','RECEIVED','RECONCILIATION_REQUIRED'))
        OR (OLD.status='PARTIALLY_RECEIVED' AND NEW.status IN ('RECEIVED','RECONCILIATION_REQUIRED'))
        OR (OLD.status='RECONCILIATION_REQUIRED' AND NEW.status IN
            ('IN_TRANSIT','PARTIALLY_RECEIVED','RECEIVED','CLOSED_WITH_DISCREPANCY'))
    ) THEN
        RAISE EXCEPTION 'Transfer status transition is not allowed' USING ERRCODE='23514';
    END IF;
    IF NEW.status='PICKED' AND EXISTS (
        SELECT 1 FROM stockrescue.transfer_line l
        WHERE l.org_id=NEW.org_id AND l.transfer_id=NEW.id
          AND NOT EXISTS (
              SELECT 1 FROM stockrescue.transfer_physical_document_line d
              WHERE d.org_id=l.org_id AND d.transfer_id=l.transfer_id AND d.line_id=l.id
                AND d.document_type='PICK' AND d.quantity=l.requested_quantity)) THEN
        RAISE EXCEPTION 'Every transfer line requires complete pick evidence before PICKED' USING ERRCODE='23514';
    END IF;
    IF NEW.status='IN_TRANSIT' AND EXISTS (
        SELECT 1 FROM stockrescue.transfer_line l
        JOIN stockrescue.transfer_custody_account a
          ON a.org_id=l.org_id AND a.transfer_id=l.transfer_id AND a.line_id=l.id
        WHERE l.org_id=NEW.org_id AND l.transfer_id=NEW.id
          AND (a.dispatched<>l.requested_quantity OR NOT EXISTS (
              SELECT 1 FROM stockrescue.transfer_physical_document_line d
              WHERE d.org_id=l.org_id AND d.transfer_id=l.transfer_id AND d.line_id=l.id
                AND d.document_type='DISPATCH' AND d.quantity=l.requested_quantity))) THEN
        RAISE EXCEPTION 'Every transfer line requires linked stock and custody dispatch evidence before IN_TRANSIT' USING ERRCODE='23514';
    END IF;
    IF NEW.status='RECEIVED' AND EXISTS (
        SELECT 1 FROM stockrescue.transfer_line l
        JOIN stockrescue.transfer_custody_account a
          ON a.org_id=l.org_id AND a.transfer_id=l.transfer_id AND a.line_id=l.id
        WHERE l.org_id=NEW.org_id AND l.transfer_id=NEW.id
          AND (a.outstanding<>0 OR a.physically_received<>a.dispatched
              OR a.confirmed_loss<>0 OR a.physically_returned<>0)) THEN
        RAISE EXCEPTION 'RECEIVED requires every dispatched unit to be physically received with no outstanding custody' USING ERRCODE='23514';
    END IF;
    IF NEW.status='CLOSED_WITH_DISCREPANCY' AND (
        EXISTS (SELECT 1 FROM stockrescue.transfer_custody_account a
            WHERE a.org_id=NEW.org_id AND a.transfer_id=NEW.id AND a.outstanding<>0)
        OR NOT EXISTS (SELECT 1 FROM stockrescue.transfer_reconciliation_evidence e
            WHERE e.org_id=NEW.org_id AND e.transfer_id=NEW.id
                AND e.evidence_type IN ('DISCREPANCY','RETURN_REQUEST','CUSTODY_RECONCILIATION'))
        AND NOT EXISTS (SELECT 1 FROM stockrescue.transfer_custody_movement m
            WHERE m.org_id=NEW.org_id AND m.transfer_id=NEW.id AND m.movement_type IN ('LOSS','RETURN'))) THEN
        RAISE EXCEPTION 'CLOSED_WITH_DISCREPANCY requires documented settlement and zero outstanding custody on every line' USING ERRCODE='23514';
    END IF;
    NEW.updated_at:=greatest(clock_timestamp(),OLD.updated_at);
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfer_order_guard BEFORE UPDATE ON stockrescue.transfer_order
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_transfer_order();
CREATE TRIGGER transfer_order_no_delete BEFORE DELETE ON stockrescue.transfer_order
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_order_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_order
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE FUNCTION stockrescue.assert_transfer_transition() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE p_org UUID; p_transfer UUID; order_status VARCHAR(32); order_version BIGINT;
    latest_status VARCHAR(32); latest_version BIGINT;
BEGIN
    IF TG_TABLE_NAME='transfer_order' THEN
        p_org:=NEW.org_id; p_transfer:=NEW.id;
    ELSE
        p_org:=NEW.org_id; p_transfer:=NEW.transfer_id;
    END IF;
    SELECT status,version INTO order_status,order_version FROM stockrescue.transfer_order
        WHERE org_id=p_org AND id=p_transfer;
    SELECT status_after,version INTO latest_status,latest_version FROM stockrescue.transfer_transition
        WHERE org_id=p_org AND transfer_id=p_transfer ORDER BY version DESC LIMIT 1;
    IF latest_version IS NULL OR latest_version<>order_version OR latest_status<>order_status THEN
        RAISE EXCEPTION 'Transfer current state must match its latest immutable transition' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER transfer_order_transition_reconcile
    AFTER INSERT OR UPDATE ON stockrescue.transfer_order DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION stockrescue.assert_transfer_transition();
CREATE CONSTRAINT TRIGGER transfer_transition_order_reconcile
    AFTER INSERT ON stockrescue.transfer_transition DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION stockrescue.assert_transfer_transition();

CREATE FUNCTION stockrescue.guard_transfer_line() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.id,NEW.org_id,NEW.transfer_id,NEW.source_store_id,NEW.destination_store_id,NEW.line_number,
        NEW.source_position_id,NEW.destination_location_id,NEW.requested_quantity,NEW.unit,
        NEW.expected_source_version,NEW.created_at)
        IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.transfer_id,OLD.source_store_id,OLD.destination_store_id,OLD.line_number,
        OLD.source_position_id,OLD.destination_location_id,OLD.requested_quantity,OLD.unit,
        OLD.expected_source_version,OLD.created_at)
        OR (OLD.reservation_allocation_id IS NOT NULL AND
            (NEW.reservation_allocation_id,NEW.reservation_bucket)
                IS DISTINCT FROM (OLD.reservation_allocation_id,OLD.reservation_bucket))
        OR (OLD.reservation_allocation_id IS NULL AND NEW.reservation_allocation_id IS NULL
            AND NEW.reservation_bucket IS NOT NULL) THEN
        RAISE EXCEPTION 'Transfer line identity and reservation ownership cannot be rewritten' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfer_line_no_delete BEFORE DELETE ON stockrescue.transfer_line
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_line_reservation_guard BEFORE UPDATE ON stockrescue.transfer_line
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_transfer_line();
CREATE TRIGGER transfer_line_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_line
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE FUNCTION stockrescue.assert_transfer_line_account() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM stockrescue.transfer_custody_account a
        WHERE a.org_id=NEW.org_id AND a.transfer_id=NEW.transfer_id AND a.line_id=NEW.id) THEN
        RAISE EXCEPTION 'Every transfer line requires a zero-initialized custody account' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER transfer_line_account_required
    AFTER INSERT ON stockrescue.transfer_line DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION stockrescue.assert_transfer_line_account();

CREATE FUNCTION stockrescue.guard_transfer_custody_account() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.org_id,NEW.transfer_id,NEW.line_id) IS DISTINCT FROM (OLD.org_id,OLD.transfer_id,OLD.line_id)
        OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1
        OR NEW.updated_at<OLD.updated_at THEN
        RAISE EXCEPTION 'Custody account identity is immutable and its version must advance once' USING ERRCODE='23514';
    END IF;
    NEW.updated_at:=greatest(clock_timestamp(),OLD.updated_at);
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfer_custody_account_guard BEFORE UPDATE ON stockrescue.transfer_custody_account
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_transfer_custody_account();
CREATE TRIGGER transfer_custody_account_no_delete BEFORE DELETE ON stockrescue.transfer_custody_account
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_custody_account_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_custody_account
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE FUNCTION stockrescue.validate_transfer_custody_movement() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE line_row RECORD; transfer_command RECORD; stock_movement RECORD; stock_line RECORD;
    source_position RECORD; destination_position RECORD; expected_inventory_operation VARCHAR(24);
    expected_transfer_operation VARCHAR(32);
BEGIN
    SELECT * INTO line_row FROM stockrescue.transfer_line
        WHERE org_id=NEW.org_id AND transfer_id=NEW.transfer_id AND id=NEW.line_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Custody movement requires its transfer line' USING ERRCODE='23503';
    END IF;
    SELECT * INTO transfer_command FROM stockrescue.transfer_command_receipt
        WHERE org_id=NEW.org_id AND transfer_id=NEW.transfer_id AND id=NEW.command_id;
    IF NOT FOUND OR transfer_command.actor_user_id<>NEW.actor_user_id
        OR transfer_command.membership_id<>NEW.membership_id THEN
        RAISE EXCEPTION 'Custody movement actor and command must match' USING ERRCODE='23514';
    END IF;

    IF NEW.movement_type='LOSS' THEN
        IF transfer_command.operation<>'CONFIRM_LOSS' OR transfer_command.stock_command_receipt_id IS NOT NULL THEN
            RAISE EXCEPTION 'Loss custody must be linked to a loss command without a stock posting' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM stockrescue.transfer_physical_document d
            JOIN stockrescue.transfer_physical_document_line l
              ON l.org_id=d.org_id AND l.transfer_id=d.transfer_id AND l.document_id=d.id
            WHERE d.org_id=NEW.org_id AND d.transfer_id=NEW.transfer_id AND d.command_id=NEW.command_id
                AND d.document_type='DAMAGE' AND d.document_store_id=line_row.destination_store_id
                AND l.line_id=NEW.line_id AND l.quantity=NEW.quantity AND l.disposition='DAMAGED') THEN
            RAISE EXCEPTION 'Confirmed loss requires linked destination damage evidence' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;

    CASE NEW.movement_type
        WHEN 'DISPATCH' THEN expected_inventory_operation:='TRANSFER_OUT'; expected_transfer_operation:='DISPATCH';
        WHEN 'RECEIPT' THEN expected_inventory_operation:='TRANSFER_IN'; expected_transfer_operation:='RECEIVE';
        WHEN 'RETURN' THEN expected_inventory_operation:='TRANSFER_RETURN'; expected_transfer_operation:='CONFIRM_RETURN';
        ELSE RAISE EXCEPTION 'Unsupported custody movement type' USING ERRCODE='23514';
    END CASE;
    IF transfer_command.operation<>expected_transfer_operation
        OR transfer_command.stock_store_id<>NEW.stock_store_id
        OR transfer_command.stock_operation<>expected_inventory_operation THEN
        RAISE EXCEPTION 'Custody movement and inventory command operations must match' USING ERRCODE='23514';
    END IF;
    SELECT m.operation,m.command_id INTO stock_movement FROM stockrescue.stock_movement m
        WHERE m.org_id=NEW.org_id AND m.store_id=NEW.stock_store_id AND m.id=NEW.stock_movement_id;
    SELECT l.delta_on_hand,l.delta_reserved,l.delta_quarantined,l.delta_other_blocked
        INTO stock_line FROM stockrescue.stock_movement_line l
        WHERE l.org_id=NEW.org_id AND l.store_id=NEW.stock_store_id
            AND l.movement_id=NEW.stock_movement_id AND l.position_id=NEW.stock_position_id;
    IF NOT FOUND OR stock_movement.command_id<>transfer_command.stock_command_receipt_id
        OR stock_movement.operation<>expected_inventory_operation THEN
        RAISE EXCEPTION 'Custody movement must reference its committed inventory command and movement' USING ERRCODE='23514';
    END IF;

    IF NEW.movement_type='DISPATCH' THEN
        SELECT * INTO source_position FROM stockrescue.stock_position
            WHERE org_id=NEW.org_id AND store_id=line_row.source_store_id AND id=line_row.source_position_id;
        IF NEW.quantity<>line_row.requested_quantity OR NEW.stock_store_id<>line_row.source_store_id
            OR NEW.stock_position_id<>line_row.source_position_id
            OR stock_line.delta_on_hand<>-NEW.quantity OR stock_line.delta_reserved<>-NEW.quantity
            OR stock_line.delta_quarantined<>0 OR stock_line.delta_other_blocked<>0
            OR line_row.reservation_allocation_id IS NULL
            OR NOT EXISTS (SELECT 1 FROM stockrescue.stock_allocation_transition a
                WHERE a.org_id=NEW.org_id AND a.store_id=NEW.stock_store_id
                    AND a.allocation_id=line_row.reservation_allocation_id AND a.movement_id=NEW.stock_movement_id
                    AND a.position_id=line_row.source_position_id AND a.transition='CONSUME'
                    AND a.remaining_after=0 AND a.state_after='CONSUMED') THEN
            RAISE EXCEPTION 'Dispatch custody must match the full reserved source line' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.movement_type='RECEIPT' THEN
        SELECT p.product_id,p.lot_id,product.sku,lot.lot_code INTO source_position
          FROM stockrescue.stock_position p
          JOIN stockrescue.product product ON product.org_id=p.org_id AND product.id=p.product_id
          JOIN stockrescue.manufacturer_lot lot ON lot.org_id=p.org_id AND lot.product_id=p.product_id AND lot.id=p.lot_id
         WHERE p.org_id=NEW.org_id AND p.store_id=line_row.source_store_id AND p.id=line_row.source_position_id;
        SELECT p.product_id,p.lot_id,p.location_id INTO destination_position FROM stockrescue.stock_position p
            WHERE p.org_id=NEW.org_id AND p.store_id=line_row.destination_store_id AND p.id=NEW.stock_position_id;
        IF NEW.stock_store_id<>line_row.destination_store_id OR stock_line.delta_on_hand<>NEW.quantity
            OR stock_line.delta_reserved<>0 OR stock_line.delta_quarantined<0 OR stock_line.delta_other_blocked<0
            OR stock_line.delta_quarantined::numeric+stock_line.delta_other_blocked::numeric>stock_line.delta_on_hand::numeric
            OR destination_position.product_id IS DISTINCT FROM source_position.product_id
            OR destination_position.lot_id IS DISTINCT FROM source_position.lot_id
            OR destination_position.location_id IS DISTINCT FROM line_row.destination_location_id
            OR NOT EXISTS (
                SELECT 1 FROM stockrescue.transfer_physical_document d
                JOIN stockrescue.transfer_physical_document_line l
                  ON l.org_id=d.org_id AND l.transfer_id=d.transfer_id AND l.document_id=d.id
                WHERE d.org_id=NEW.org_id AND d.transfer_id=NEW.transfer_id AND d.command_id=NEW.command_id
                    AND d.document_type='RECEIPT' AND d.document_store_id=line_row.destination_store_id
                    AND l.line_id=NEW.line_id AND l.quantity=NEW.quantity
                    AND l.disposition=CASE WHEN stock_line.delta_quarantined=NEW.quantity THEN 'QUARANTINED' ELSE 'AVAILABLE' END
                    AND l.observed_sku=source_position.sku AND l.observed_lot_code=source_position.lot_code)
            OR stock_line.delta_other_blocked<>0 THEN
            RAISE EXCEPTION 'Receipt custody must match destination stock and source product/lot identity' USING ERRCODE='23514';
        END IF;
    ELSE
        SELECT p.product_id,p.lot_id,product.sku,lot.lot_code INTO source_position
          FROM stockrescue.stock_position p
          JOIN stockrescue.product product ON product.org_id=p.org_id AND product.id=p.product_id
          JOIN stockrescue.manufacturer_lot lot ON lot.org_id=p.org_id AND lot.product_id=p.product_id AND lot.id=p.lot_id
         WHERE p.org_id=NEW.org_id AND p.store_id=line_row.source_store_id AND p.id=line_row.source_position_id;
        IF NEW.stock_store_id<>line_row.source_store_id OR NEW.stock_position_id<>line_row.source_position_id
            OR stock_line.delta_on_hand<>NEW.quantity OR stock_line.delta_reserved<>0
            OR stock_line.delta_quarantined<0 OR stock_line.delta_other_blocked<0
            OR stock_line.delta_quarantined::numeric+stock_line.delta_other_blocked::numeric>stock_line.delta_on_hand::numeric
            OR NOT EXISTS (
                SELECT 1 FROM stockrescue.transfer_physical_document d
                JOIN stockrescue.transfer_physical_document_line l
                  ON l.org_id=d.org_id AND l.transfer_id=d.transfer_id AND l.document_id=d.id
                WHERE d.org_id=NEW.org_id AND d.transfer_id=NEW.transfer_id AND d.command_id=NEW.command_id
                    AND d.document_type='RETURN' AND d.document_store_id=line_row.source_store_id
                    AND l.line_id=NEW.line_id AND l.quantity=NEW.quantity AND l.disposition='RETURNED'
                    AND l.observed_sku=source_position.sku AND l.observed_lot_code=source_position.lot_code
                    AND EXISTS (SELECT 1 FROM stockrescue.transfer_reconciliation_evidence e
                        WHERE e.org_id=d.org_id AND e.transfer_id=d.transfer_id AND e.line_id=l.line_id
                            AND e.evidence_type='RETURN_REQUEST'
                            AND e.observed_state->>'returnReference'=d.document_reference
                            AND (e.expected_state->>'outstanding')::bigint=NEW.quantity)) THEN
            RAISE EXCEPTION 'Return custody must match physical stock returned to its source position' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfer_custody_movement_validate BEFORE INSERT ON stockrescue.transfer_custody_movement
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_transfer_custody_movement();
CREATE TRIGGER transfer_custody_movement_immutable BEFORE UPDATE OR DELETE ON stockrescue.transfer_custody_movement
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_custody_movement_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_custody_movement
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE FUNCTION stockrescue.assert_transfer_custody_account() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE a stockrescue.transfer_custody_account%ROWTYPE; total_dispatched NUMERIC; total_received NUMERIC;
    total_loss NUMERIC; total_returned NUMERIC; latest_outstanding BIGINT; latest_version BIGINT;
BEGIN
    SELECT * INTO a FROM stockrescue.transfer_custody_account
        WHERE org_id=NEW.org_id AND transfer_id=NEW.transfer_id AND line_id=NEW.line_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Custody movement requires its materialized account' USING ERRCODE='23514';
    END IF;
    SELECT coalesce(sum(quantity) FILTER (WHERE movement_type='DISPATCH'),0),
        coalesce(sum(quantity) FILTER (WHERE movement_type='RECEIPT'),0),
        coalesce(sum(quantity) FILTER (WHERE movement_type='LOSS'),0),
        coalesce(sum(quantity) FILTER (WHERE movement_type='RETURN'),0),
        max(version)
      INTO total_dispatched,total_received,total_loss,total_returned,latest_version
      FROM stockrescue.transfer_custody_movement
      WHERE org_id=NEW.org_id AND transfer_id=NEW.transfer_id AND line_id=NEW.line_id;
    SELECT outstanding_after INTO latest_outstanding FROM stockrescue.transfer_custody_movement
      WHERE org_id=NEW.org_id AND transfer_id=NEW.transfer_id AND line_id=NEW.line_id
      ORDER BY version DESC LIMIT 1;
    IF a.dispatched::numeric<>total_dispatched OR a.physically_received::numeric<>total_received
        OR a.confirmed_loss::numeric<>total_loss OR a.physically_returned::numeric<>total_returned
        OR a.outstanding<>coalesce(latest_outstanding,0) OR a.version<>coalesce(latest_version,0) THEN
        RAISE EXCEPTION 'Custody account must equal its immutable movement journal' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER transfer_custody_account_reconcile
    AFTER INSERT OR UPDATE ON stockrescue.transfer_custody_account DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION stockrescue.assert_transfer_custody_account();
CREATE CONSTRAINT TRIGGER transfer_custody_movement_reconcile
    AFTER INSERT ON stockrescue.transfer_custody_movement DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION stockrescue.assert_transfer_custody_account();

CREATE TRIGGER transfer_command_immutable BEFORE UPDATE OR DELETE ON stockrescue.transfer_command_receipt
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_command_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_command_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_transition_immutable BEFORE UPDATE OR DELETE ON stockrescue.transfer_transition
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_transition_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_transition
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_document_immutable BEFORE UPDATE OR DELETE ON stockrescue.transfer_physical_document
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_document_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_physical_document
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_document_line_immutable BEFORE UPDATE OR DELETE ON stockrescue.transfer_physical_document_line
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_document_line_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_physical_document_line
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_reconciliation_immutable BEFORE UPDATE OR DELETE ON stockrescue.transfer_reconciliation_evidence
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_reconciliation_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_reconciliation_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

COMMENT ON TABLE stockrescue.transfer_custody_account IS 'Versioned transit projection; immutable custody movements remain the source for reconciliation.';
COMMENT ON TABLE stockrescue.transfer_custody_movement IS 'Append-only, command-linked physical custody facts for one transfer line.';

-- Transfer aggregate events are committed with their state transition. They
-- share the inventory Kafka relay and topic but retain a transferId key and
-- independent aggregate ordering.
CREATE TABLE stockrescue.transfer_outbox_event (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version>=0),
    event_type VARCHAR(48) NOT NULL CHECK (event_type IN
        ('TransferCommandRecorded','TransferDispatched','TransferReceived','TransferReturned','TransferDiscrepancyResolved')),
    schema_version INTEGER NOT NULL CHECK (schema_version=1),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='object'),
    correlation_id VARCHAR(64) CHECK (correlation_id IS NULL OR correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    recorded_at TIMESTAMPTZ NOT NULL CHECK (isfinite(recorded_at)),
    CONSTRAINT transfer_outbox_scope UNIQUE (org_id,transfer_id,id),
    CONSTRAINT transfer_outbox_event_scope UNIQUE (org_id,transfer_id,id,aggregate_version),
    CONSTRAINT transfer_outbox_aggregate_unique UNIQUE (org_id,transfer_id,aggregate_version),
    CONSTRAINT transfer_outbox_envelope CHECK (
        payload ?& ARRAY['eventId','eventType','schemaVersion','occurredAt','orgId','transferId','aggregateVersion',
            'correlationId','causationId','commandId','dataClassification','data']
        AND coalesce(payload->>'eventId'=id::text AND payload->>'eventType'=event_type
            AND payload->>'schemaVersion'='1' AND payload->>'orgId'=org_id::text
            AND payload->>'transferId'=transfer_id::text AND payload->>'aggregateVersion'=aggregate_version::text
            AND payload->>'causationId'=payload->>'commandId'
            AND payload->>'dataClassification'='INTERNAL'
            AND jsonb_typeof(payload->'schemaVersion')='number'
            AND jsonb_typeof(payload->'aggregateVersion')='number'
            AND jsonb_typeof(payload->'occurredAt')='string' AND jsonb_typeof(payload->'data')='object',FALSE)
        AND (payload->>'correlationId') IS NOT DISTINCT FROM correlation_id),
    CONSTRAINT transfer_outbox_transition_fk FOREIGN KEY (org_id,transfer_id,aggregate_version)
        REFERENCES stockrescue.transfer_transition(org_id,transfer_id,version) ON DELETE RESTRICT
);

CREATE TABLE stockrescue.transfer_outbox_delivery (
    event_id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(next_attempt_at)),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    claim_version BIGINT NOT NULL DEFAULT 0 CHECK (claim_version>=0),
    published_at TIMESTAMPTZ CHECK (published_at IS NULL OR isfinite(published_at)),
    parked_at TIMESTAMPTZ CHECK (parked_at IS NULL OR isfinite(parked_at)),
    last_error_code VARCHAR(64) CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    last_error_at TIMESTAMPTZ CHECK (last_error_at IS NULL OR isfinite(last_error_at)),
    CONSTRAINT transfer_outbox_delivery_lease CHECK ((lease_owner IS NULL)=(lease_until IS NULL)
        AND (lease_until IS NULL OR isfinite(lease_until))),
    CONSTRAINT transfer_outbox_delivery_terminal CHECK (published_at IS NULL OR (lease_owner IS NULL AND parked_at IS NULL)),
    CONSTRAINT transfer_outbox_delivery_parked CHECK (parked_at IS NULL OR
        (published_at IS NULL AND lease_owner IS NULL AND last_error_code IS NOT NULL)),
    CONSTRAINT transfer_outbox_delivery_error CHECK ((last_error_code IS NULL)=(last_error_at IS NULL)),
    CONSTRAINT transfer_outbox_delivery_scope UNIQUE (org_id,transfer_id,event_id),
    CONSTRAINT transfer_outbox_delivery_fk FOREIGN KEY (org_id,transfer_id,event_id)
        REFERENCES stockrescue.transfer_outbox_event(org_id,transfer_id,id) ON DELETE RESTRICT
);
CREATE INDEX transfer_outbox_claim_idx ON stockrescue.transfer_outbox_delivery(next_attempt_at,lease_until,event_id)
    WHERE published_at IS NULL AND parked_at IS NULL;

CREATE FUNCTION stockrescue.guard_transfer_outbox_delivery() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE new_claim BOOLEAN; release_claim BOOLEAN; shorten_lease BOOLEAN;
BEGIN
    IF (NEW.event_id,NEW.org_id,NEW.transfer_id) IS DISTINCT FROM (OLD.event_id,OLD.org_id,OLD.transfer_id)
        OR NEW.attempt_count<OLD.attempt_count OR NEW.claim_version<OLD.claim_version
        OR OLD.published_at IS NOT NULL AND NEW IS DISTINCT FROM OLD
        OR OLD.parked_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Transfer delivery identity, attempts, fencing and terminal state are monotonic' USING ERRCODE='23514';
    END IF;
    new_claim := NEW.claim_version=OLD.claim_version+1 AND NEW.attempt_count=OLD.attempt_count+1
        AND NEW.lease_owner IS NOT NULL AND NEW.lease_until>clock_timestamp()
        AND NEW.lease_until<=clock_timestamp()+interval '5 minutes'
        AND (OLD.lease_owner IS NULL OR OLD.lease_until<=clock_timestamp())
        AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at AND NEW.parked_at IS NOT DISTINCT FROM OLD.parked_at
        AND NEW.last_error_code IS NOT DISTINCT FROM OLD.last_error_code AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at;
    release_claim := NEW.claim_version=OLD.claim_version AND NEW.attempt_count=OLD.attempt_count
        AND OLD.lease_owner IS NOT NULL AND OLD.lease_until>clock_timestamp()
        AND NEW.lease_owner IS NULL AND NEW.lease_until IS NULL
        AND ((OLD.published_at IS NULL AND NEW.published_at IS NOT NULL AND NEW.parked_at IS NULL
                AND NEW.next_attempt_at=OLD.next_attempt_at AND NEW.last_error_code IS NULL AND NEW.last_error_at IS NULL)
            OR (OLD.parked_at IS NULL AND NEW.parked_at IS NOT NULL AND NEW.published_at IS NULL
                AND NEW.next_attempt_at=OLD.next_attempt_at AND NEW.last_error_code IS NOT NULL AND NEW.last_error_at IS NOT NULL)
            OR (NEW.published_at IS NULL AND NEW.parked_at IS NULL AND NEW.next_attempt_at>OLD.next_attempt_at
                AND NEW.last_error_code IS NOT NULL AND NEW.last_error_at IS NOT NULL));
    shorten_lease := NEW.claim_version=OLD.claim_version AND NEW.attempt_count=OLD.attempt_count
        AND OLD.lease_owner IS NOT NULL AND NEW.lease_owner=OLD.lease_owner
        AND NEW.lease_until IS NOT NULL AND NEW.lease_until<OLD.lease_until
        AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at AND NEW.parked_at IS NOT DISTINCT FROM OLD.parked_at
        AND NEW.next_attempt_at=OLD.next_attempt_at AND NEW.last_error_code IS NOT DISTINCT FROM OLD.last_error_code
        AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at;
    IF new_claim THEN RETURN NEW; END IF;
    IF NEW.claim_version<>OLD.claim_version OR NEW.attempt_count<>OLD.attempt_count THEN
        RAISE EXCEPTION 'Transfer delivery fencing advances exactly once per claim' USING ERRCODE='23514';
    END IF;
    IF (NEW.lease_owner,NEW.lease_until,NEW.published_at,NEW.parked_at,NEW.next_attempt_at,NEW.last_error_code,NEW.last_error_at)
        IS DISTINCT FROM (OLD.lease_owner,OLD.lease_until,OLD.published_at,OLD.parked_at,OLD.next_attempt_at,OLD.last_error_code,OLD.last_error_at)
        AND NOT release_claim AND NOT shorten_lease THEN
        RAISE EXCEPTION 'Only the current transfer lease may publish, park or schedule a retry' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfer_outbox_delivery_guard BEFORE UPDATE ON stockrescue.transfer_outbox_delivery
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_transfer_outbox_delivery();
CREATE TRIGGER transfer_outbox_event_immutable BEFORE UPDATE OR DELETE ON stockrescue.transfer_outbox_event
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_outbox_event_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_outbox_event
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_outbox_delivery_no_delete BEFORE DELETE ON stockrescue.transfer_outbox_delivery
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_outbox_delivery_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_outbox_delivery
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE TABLE stockrescue.transfer_outbox_redrive_attempt (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    event_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation>0),
    request_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    request_fingerprint BYTEA NOT NULL CHECK (octet_length(request_fingerprint)=32),
    source_fingerprint BYTEA NOT NULL CHECK (octet_length(source_fingerprint)=32),
    source_claim_version BIGINT NOT NULL CHECK (source_claim_version>=0),
    source_attempt_count INTEGER NOT NULL CHECK (source_attempt_count>=0),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    evidence_reference VARCHAR(256) NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference COLLATE "C" !~ '[[:cntrl:]]'),
    accepted_state VARCHAR(24) NOT NULL CHECK (accepted_state IN ('REDRIVE_QUEUED','BLOCKED')),
    accepted_next_attempt_at TIMESTAMPTZ NOT NULL CHECK (isfinite(accepted_next_attempt_at)),
    accepted_blocked_at TIMESTAMPTZ CHECK (accepted_blocked_at IS NULL OR isfinite(accepted_blocked_at)),
    accepted_error_code VARCHAR(64) CHECK (accepted_error_code IS NULL OR accepted_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    claim_version BIGINT NOT NULL DEFAULT 0 CHECK (claim_version>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(next_attempt_at)),
    lease_owner UUID,
    lease_until TIMESTAMPTZ CHECK (lease_until IS NULL OR isfinite(lease_until)),
    published_at TIMESTAMPTZ CHECK (published_at IS NULL OR isfinite(published_at)),
    blocked_at TIMESTAMPTZ CHECK (blocked_at IS NULL OR isfinite(blocked_at)),
    last_error_code VARCHAR(64) CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    last_error_at TIMESTAMPTZ CHECK (last_error_at IS NULL OR isfinite(last_error_at)),
    CONSTRAINT transfer_outbox_redrive_generation_unique UNIQUE (event_id,generation),
    CONSTRAINT transfer_outbox_redrive_request_unique UNIQUE (org_id,actor_user_id,request_id),
    CONSTRAINT transfer_outbox_redrive_lease CHECK ((lease_owner IS NULL)=(lease_until IS NULL)),
    CONSTRAINT transfer_outbox_redrive_published CHECK (published_at IS NULL OR
        (lease_owner IS NULL AND blocked_at IS NULL)),
    CONSTRAINT transfer_outbox_redrive_blocked CHECK (blocked_at IS NULL OR
        (published_at IS NULL AND lease_owner IS NULL AND last_error_code IS NOT NULL AND last_error_at IS NOT NULL)),
    CONSTRAINT transfer_outbox_redrive_accepted CHECK (
        (accepted_state='REDRIVE_QUEUED' AND accepted_blocked_at IS NULL AND accepted_error_code IS NULL)
        OR (accepted_state='BLOCKED' AND accepted_blocked_at IS NOT NULL AND accepted_error_code IS NOT NULL)),
    CONSTRAINT transfer_outbox_redrive_event_fk FOREIGN KEY (org_id,transfer_id,event_id)
        REFERENCES stockrescue.transfer_outbox_event(org_id,transfer_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_outbox_redrive_delivery_fk FOREIGN KEY (org_id,transfer_id,event_id)
        REFERENCES stockrescue.transfer_outbox_delivery(org_id,transfer_id,event_id) ON DELETE RESTRICT,
    CONSTRAINT transfer_outbox_redrive_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT
);
CREATE INDEX transfer_outbox_redrive_claim_idx
    ON stockrescue.transfer_outbox_redrive_attempt(next_attempt_at,lease_until,id)
    WHERE published_at IS NULL AND blocked_at IS NULL;
CREATE FUNCTION stockrescue.guard_transfer_outbox_redrive_attempt() RETURNS trigger
    LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE new_claim BOOLEAN; release_claim BOOLEAN;
BEGIN
    IF (NEW.id,NEW.org_id,NEW.transfer_id,NEW.event_id,NEW.generation,NEW.request_id,NEW.actor_user_id,NEW.membership_id,
        NEW.request_fingerprint,NEW.source_fingerprint,NEW.source_claim_version,NEW.source_attempt_count,NEW.reason,
        NEW.evidence_reference,NEW.accepted_state,NEW.accepted_next_attempt_at,NEW.accepted_blocked_at,
        NEW.accepted_error_code,NEW.recorded_at) IS DISTINCT FROM
       (OLD.id,OLD.org_id,OLD.transfer_id,OLD.event_id,OLD.generation,OLD.request_id,OLD.actor_user_id,OLD.membership_id,
        OLD.request_fingerprint,OLD.source_fingerprint,OLD.source_claim_version,OLD.source_attempt_count,OLD.reason,
        OLD.evidence_reference,OLD.accepted_state,OLD.accepted_next_attempt_at,OLD.accepted_blocked_at,
        OLD.accepted_error_code,OLD.recorded_at)
        OR NEW.attempt_count<OLD.attempt_count OR NEW.claim_version<OLD.claim_version
        OR OLD.published_at IS NOT NULL AND NEW IS DISTINCT FROM OLD
        OR OLD.blocked_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Transfer redrive evidence and terminal state are immutable' USING ERRCODE='23514';
    END IF;
    new_claim := NEW.claim_version=OLD.claim_version+1 AND NEW.attempt_count=OLD.attempt_count+1
        AND NEW.lease_owner IS NOT NULL AND NEW.lease_until>clock_timestamp()
        AND NEW.lease_until<=clock_timestamp()+interval '5 minutes'
        AND (OLD.lease_owner IS NULL OR OLD.lease_until<=clock_timestamp())
        AND NEW.next_attempt_at=OLD.next_attempt_at AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
        AND NEW.blocked_at IS NOT DISTINCT FROM OLD.blocked_at
        AND NEW.last_error_code IS NOT DISTINCT FROM OLD.last_error_code AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at;
    release_claim := NEW.claim_version=OLD.claim_version AND NEW.attempt_count=OLD.attempt_count
        AND OLD.lease_owner IS NOT NULL AND OLD.lease_until>clock_timestamp()
        AND NEW.lease_owner IS NULL AND NEW.lease_until IS NULL
        AND ((OLD.published_at IS NULL AND NEW.published_at IS NOT NULL AND NEW.blocked_at IS NULL
                AND NEW.next_attempt_at=OLD.next_attempt_at AND NEW.last_error_code IS NULL AND NEW.last_error_at IS NULL)
            OR (OLD.blocked_at IS NULL AND NEW.blocked_at IS NOT NULL AND NEW.published_at IS NULL
                AND NEW.next_attempt_at=OLD.next_attempt_at AND NEW.last_error_code IS NOT NULL AND NEW.last_error_at IS NOT NULL)
            OR (NEW.published_at IS NULL AND NEW.blocked_at IS NULL AND NEW.next_attempt_at>OLD.next_attempt_at
                AND NEW.last_error_code IS NOT NULL AND NEW.last_error_at IS NOT NULL));
    IF new_claim THEN RETURN NEW; END IF;
    IF NEW.claim_version<>OLD.claim_version OR NEW.attempt_count<>OLD.attempt_count THEN
        RAISE EXCEPTION 'Transfer redrive fencing advances exactly once per claim' USING ERRCODE='23514';
    END IF;
    IF (NEW.lease_owner,NEW.lease_until,NEW.published_at,NEW.blocked_at,NEW.next_attempt_at,NEW.last_error_code,NEW.last_error_at)
        IS DISTINCT FROM (OLD.lease_owner,OLD.lease_until,OLD.published_at,OLD.blocked_at,OLD.next_attempt_at,OLD.last_error_code,OLD.last_error_at)
        AND NOT release_claim THEN
        RAISE EXCEPTION 'Only the current transfer redrive lease may publish, block or schedule a retry' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfer_outbox_redrive_guard BEFORE UPDATE ON stockrescue.transfer_outbox_redrive_attempt
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_transfer_outbox_redrive_attempt();
CREATE TRIGGER transfer_outbox_redrive_no_delete BEFORE DELETE ON stockrescue.transfer_outbox_redrive_attempt
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_outbox_redrive_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_outbox_redrive_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE FUNCTION stockrescue.enqueue_transfer_event() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE event_id UUID := gen_random_uuid(); event_name TEXT; command_operation TEXT; command_correlation TEXT;
        source_id UUID; destination_id UUID; payload JSONB; movement_refs JSONB; custody_refs JSONB;
BEGIN
    SELECT c.operation,c.correlation_id,o.source_store_id,o.destination_store_id
      INTO command_operation,command_correlation,source_id,destination_id
      FROM stockrescue.transfer_command_receipt c
      JOIN stockrescue.transfer_order o ON o.org_id=c.org_id AND o.id=c.transfer_id
      WHERE c.org_id=NEW.org_id AND c.transfer_id=NEW.transfer_id AND c.id=NEW.command_id;
    event_name := CASE command_operation
        WHEN 'DISPATCH' THEN 'TransferDispatched'
        WHEN 'RECEIVE' THEN 'TransferReceived'
        WHEN 'CONFIRM_RETURN' THEN 'TransferReturned'
        WHEN 'CONFIRM_LOSS' THEN 'TransferDiscrepancyResolved'
        ELSE 'TransferCommandRecorded' END;
    SELECT coalesce(jsonb_agg(jsonb_build_object('id',m.id,'lineId',m.line_id,'movementType',m.movement_type,
            'quantity',m.quantity,'stockStoreId',m.stock_store_id,'stockMovementId',m.stock_movement_id,
            'stockPositionId',m.stock_position_id) ORDER BY m.line_id,m.version),'[]'::jsonb)
      INTO custody_refs FROM stockrescue.transfer_custody_movement m
      WHERE m.org_id=NEW.org_id AND m.transfer_id=NEW.transfer_id AND m.command_id=NEW.command_id;
    SELECT CASE WHEN c.stock_store_id IS NULL THEN '[]'::jsonb ELSE
            jsonb_build_array(jsonb_build_object('storeId',c.stock_store_id,'movementId',c.outcome->>'stockMovementId')) END
      INTO movement_refs FROM stockrescue.transfer_command_receipt c
      WHERE c.org_id=NEW.org_id AND c.transfer_id=NEW.transfer_id AND c.id=NEW.command_id;
    payload := jsonb_build_object('eventId',event_id,'eventType',event_name,'schemaVersion',1,
        'occurredAt',NEW.recorded_at,'orgId',NEW.org_id,'transferId',NEW.transfer_id,
        'aggregateVersion',NEW.version,'correlationId',command_correlation,'causationId',NEW.command_id,
        'commandId',NEW.command_id,'dataClassification','INTERNAL','data',jsonb_build_object(
            'operation',command_operation,'statusBefore',NEW.status_before,'statusAfter',NEW.status_after,
            'sourceStoreId',source_id,'destinationStoreId',destination_id,
            'stockMovements',movement_refs,'custodyMovements',custody_refs));
    INSERT INTO stockrescue.transfer_outbox_event(id,org_id,transfer_id,aggregate_version,event_type,schema_version,
        payload,correlation_id,recorded_at)
    VALUES (event_id,NEW.org_id,NEW.transfer_id,NEW.version,event_name,1,payload,command_correlation,NEW.recorded_at);
    INSERT INTO stockrescue.transfer_outbox_delivery(event_id,org_id,transfer_id)
    VALUES (event_id,NEW.org_id,NEW.transfer_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfer_transition_enqueue_event AFTER INSERT ON stockrescue.transfer_transition
    FOR EACH ROW EXECUTE FUNCTION stockrescue.enqueue_transfer_event();

CREATE TABLE stockrescue.transfer_inbox (
    consumer_name VARCHAR(64) NOT NULL CHECK (consumer_name ~ '^[a-z][a-z0-9.-]{0,63}$'),
    event_id UUID NOT NULL,
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version>=0),
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='object'),
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(received_at)),
    applied_at TIMESTAMPTZ CHECK (applied_at IS NULL OR isfinite(applied_at)),
    PRIMARY KEY (consumer_name,event_id),
    CONSTRAINT transfer_inbox_aggregate_unique UNIQUE (consumer_name,org_id,transfer_id,aggregate_version),
    CONSTRAINT transfer_inbox_event_scope UNIQUE (consumer_name,event_id,org_id,transfer_id,aggregate_version),
    CONSTRAINT transfer_inbox_transfer_fk FOREIGN KEY (org_id,transfer_id)
        REFERENCES stockrescue.transfer_order(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT transfer_inbox_event_fk FOREIGN KEY (org_id,transfer_id,event_id,aggregate_version)
        REFERENCES stockrescue.transfer_outbox_event(org_id,transfer_id,id,aggregate_version) ON DELETE RESTRICT
);
CREATE TABLE stockrescue.transfer_change_projection (
    consumer_name VARCHAR(64) NOT NULL,
    org_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version>=0),
    event_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='object'),
    recorded_at TIMESTAMPTZ NOT NULL CHECK (isfinite(recorded_at)),
    PRIMARY KEY (consumer_name,org_id,transfer_id),
    CONSTRAINT transfer_change_projection_event_fk FOREIGN KEY (consumer_name,event_id,org_id,transfer_id,aggregate_version)
        REFERENCES stockrescue.transfer_inbox(consumer_name,event_id,org_id,transfer_id,aggregate_version) ON DELETE RESTRICT
);
CREATE FUNCTION stockrescue.guard_transfer_inbox() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.consumer_name,NEW.event_id,NEW.org_id,NEW.transfer_id,NEW.aggregate_version,NEW.payload_digest,NEW.payload,NEW.received_at)
        IS DISTINCT FROM (OLD.consumer_name,OLD.event_id,OLD.org_id,OLD.transfer_id,OLD.aggregate_version,OLD.payload_digest,OLD.payload,OLD.received_at)
        OR OLD.applied_at IS NOT NULL AND NEW.applied_at IS DISTINCT FROM OLD.applied_at
        OR OLD.applied_at IS NULL AND NEW.applied_at IS NULL THEN
        RAISE EXCEPTION 'Transfer inbox identity and payload are immutable; application is monotonic' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfer_inbox_guard BEFORE UPDATE ON stockrescue.transfer_inbox
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_transfer_inbox();
CREATE TRIGGER transfer_inbox_no_delete BEFORE DELETE ON stockrescue.transfer_inbox
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_inbox_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_inbox
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE INDEX transfer_inbox_deferred_idx ON stockrescue.transfer_inbox(consumer_name,org_id,transfer_id,aggregate_version)
    WHERE applied_at IS NULL;
CREATE TRIGGER transfer_change_projection_no_delete BEFORE DELETE ON stockrescue.transfer_change_projection
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER transfer_change_projection_no_truncate BEFORE TRUNCATE ON stockrescue.transfer_change_projection
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

COMMENT ON TABLE stockrescue.transfer_outbox_event IS 'Immutable transfer aggregate facts published after the PostgreSQL transaction commits.';
COMMENT ON TABLE stockrescue.transfer_outbox_delivery IS 'Leased, fenced at-least-once publication state for transfer aggregate events.';
COMMENT ON TABLE stockrescue.transfer_inbox IS 'Dedupe and deferred application record for transfer aggregate events; stock remains controlled by the inventory journal.';
