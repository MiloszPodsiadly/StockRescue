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
