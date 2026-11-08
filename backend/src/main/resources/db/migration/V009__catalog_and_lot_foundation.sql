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
