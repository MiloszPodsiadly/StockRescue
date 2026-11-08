CREATE TABLE stockrescue.food_safety_policy_version (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    profile_id UUID NOT NULL,
    temperature_class VARCHAR(16) NOT NULL CHECK (temperature_class IN ('AMBIENT','CHILLED','FROZEN')),
    version BIGINT NOT NULL CHECK (version>0),
    partial_date_rule VARCHAR(32) NOT NULL CHECK (partial_date_rule IN ('NO_NORMALIZATION','END_OF_LABELED_PERIOD')),
    approval_status VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED' CHECK (approval_status='UNVERIFIED'),
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

ALTER TABLE stockrescue.food_safety_policy_version
    ADD CONSTRAINT food_safety_policy_approval_scope_identity UNIQUE (org_id,id,profile_id,product_category,temperature_class);

CREATE TABLE stockrescue.food_safety_policy_approval (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    policy_version_id UUID NOT NULL,
    profile_id UUID NOT NULL,
    product_category VARCHAR(24) NOT NULL CHECK (product_category IN ('UNCLASSIFIED','DAIRY','BAKERY')),
    temperature_class VARCHAR(16) NOT NULL CHECK (temperature_class IN ('AMBIENT','CHILLED','FROZEN')),
    approved_sale BOOLEAN NOT NULL,
    approved_transfer BOOLEAN NOT NULL,
    policy_digest BYTEA NOT NULL CHECK (octet_length(policy_digest)=32),
    issuer_id VARCHAR(128) COLLATE "C" NOT NULL CHECK (length(issuer_id)>0 AND issuer_id !~ '[[:cntrl:]]'),
    key_id VARCHAR(128) COLLATE "C" NOT NULL CHECK (length(key_id)>0 AND key_id !~ '[[:cntrl:]]'),
    issued_at TIMESTAMPTZ NOT NULL CHECK (isfinite(issued_at)),
    valid_from TIMESTAMPTZ NOT NULL CHECK (isfinite(valid_from)),
    valid_until TIMESTAMPTZ NOT NULL CHECK (isfinite(valid_until) AND valid_until>valid_from),
    evidence_reference VARCHAR(256) COLLATE "C" NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference !~ '[[:cntrl:]]'),
    signature BYTEA NOT NULL CHECK (octet_length(signature)=64),
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    recorded_by_membership_id UUID NOT NULL,
    request_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT food_safety_policy_approval_operation_valid CHECK (approved_sale OR approved_transfer),
    CONSTRAINT food_safety_policy_approval_scope_fk FOREIGN KEY (org_id,policy_version_id,profile_id,product_category,temperature_class)
        REFERENCES stockrescue.food_safety_policy_version(org_id,id,profile_id,product_category,temperature_class) ON DELETE RESTRICT,
    CONSTRAINT food_safety_policy_approval_actor_fk FOREIGN KEY (org_id,recorded_by_membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT,
    CONSTRAINT food_safety_policy_approval_version_unique UNIQUE (org_id,policy_version_id),
    CONSTRAINT food_safety_policy_approval_request_unique UNIQUE (org_id,actor_user_id,request_id),
    CONSTRAINT food_safety_policy_approval_org_id_unique UNIQUE (org_id,id)
);
CREATE TRIGGER food_safety_policy_approval_immutable BEFORE UPDATE OR DELETE ON stockrescue.food_safety_policy_approval
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER food_safety_policy_approval_no_truncate BEFORE TRUNCATE ON stockrescue.food_safety_policy_approval
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
COMMENT ON TABLE stockrescue.food_safety_policy_approval IS
    'Immutable Ed25519 attestation bound to one exact policy version and operation scope. Every use rechecks its signature and the externally provisioned trust registry.';

CREATE TABLE stockrescue.food_safety_policy_approval_revocation (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    approval_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    recorded_by_membership_id UUID NOT NULL,
    request_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT food_safety_policy_approval_revocation_fk FOREIGN KEY (org_id,approval_id)
        REFERENCES stockrescue.food_safety_policy_approval(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT food_safety_policy_approval_revocation_actor_fk FOREIGN KEY (org_id,recorded_by_membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT,
    CONSTRAINT food_safety_policy_approval_revocation_one UNIQUE (org_id,approval_id),
    CONSTRAINT food_safety_policy_approval_revocation_request_unique UNIQUE (org_id,actor_user_id,request_id)
);
CREATE TRIGGER food_safety_policy_approval_revocation_immutable BEFORE UPDATE OR DELETE ON stockrescue.food_safety_policy_approval_revocation
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER food_safety_policy_approval_revocation_no_truncate BEFORE TRUNCATE ON stockrescue.food_safety_policy_approval_revocation
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

ALTER TABLE stockrescue.food_safety_policy_approval
    ADD COLUMN attestation_version SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN ruleset_id VARCHAR(96) COLLATE "C",
    ADD COLUMN ruleset_digest BYTEA,
    ADD CONSTRAINT food_safety_policy_approval_ruleset_shape CHECK (
        (attestation_version=1 AND ruleset_id IS NULL AND ruleset_digest IS NULL)
        OR (attestation_version=2 AND ruleset_id IS NOT NULL AND ruleset_digest IS NOT NULL AND octet_length(ruleset_digest)=32)
    );

COMMENT ON COLUMN stockrescue.food_safety_policy_approval.attestation_version IS
    'Version 1 rows are historical legacy evidence and cannot authorize an executable evaluator ruleset.';
COMMENT ON COLUMN stockrescue.food_safety_policy_approval.ruleset_id IS
    'Ruleset identity included in the signed v2 attestation; NULL only for legacy v1 evidence.';
COMMENT ON COLUMN stockrescue.food_safety_policy_approval.ruleset_digest IS
    'SHA-256 of the stable explicit evaluator ruleset manifest included in the signed v2 attestation.';

CREATE TABLE stockrescue.food_safety_product_classification (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    product_id UUID NOT NULL,
    sku VARCHAR(64) COLLATE "C" NOT NULL,
    product_category VARCHAR(24) NOT NULL CHECK (product_category IN ('DAIRY','BAKERY')),
    product_version BIGINT NOT NULL CHECK (product_version>=0),
    attestation_version SMALLINT NOT NULL CHECK (attestation_version=1),
    issuer_id VARCHAR(128) COLLATE "C" NOT NULL CHECK (length(issuer_id)>0 AND issuer_id !~ '[[:cntrl:]]'),
    key_id VARCHAR(128) COLLATE "C" NOT NULL CHECK (length(key_id)>0 AND key_id !~ '[[:cntrl:]]'),
    issued_at TIMESTAMPTZ NOT NULL CHECK (isfinite(issued_at)),
    valid_from TIMESTAMPTZ NOT NULL CHECK (isfinite(valid_from)),
    valid_until TIMESTAMPTZ NOT NULL CHECK (isfinite(valid_until) AND valid_until>valid_from AND issued_at<=valid_until),
    evidence_reference VARCHAR(256) COLLATE "C" NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference !~ '[[:cntrl:]]'),
    signature BYTEA NOT NULL CHECK (octet_length(signature)=64),
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    recorded_by_membership_id UUID NOT NULL,
    request_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT food_safety_product_classification_product_fk FOREIGN KEY (org_id,product_id)
        REFERENCES stockrescue.product(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT food_safety_product_classification_actor_fk FOREIGN KEY (org_id,recorded_by_membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT,
    CONSTRAINT food_safety_product_classification_request_unique UNIQUE (org_id,actor_user_id,request_id),
    CONSTRAINT food_safety_product_classification_org_id_unique UNIQUE (org_id,id)
);
CREATE INDEX food_safety_product_classification_latest_idx
    ON stockrescue.food_safety_product_classification(org_id,product_id,recorded_at DESC,id DESC);
CREATE TRIGGER food_safety_product_classification_immutable BEFORE UPDATE OR DELETE ON stockrescue.food_safety_product_classification
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER food_safety_product_classification_no_truncate BEFORE TRUNCATE ON stockrescue.food_safety_product_classification
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
COMMENT ON TABLE stockrescue.food_safety_product_classification IS
    'Immutable Ed25519 product classification evidence bound to one organization, stable product SKU, descriptive category and exact product version. It does not attest to all product safety properties.';

CREATE TABLE stockrescue.food_safety_product_classification_revocation (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    attestation_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    recorded_by_membership_id UUID NOT NULL,
    request_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT food_safety_product_classification_revocation_fk FOREIGN KEY (org_id,attestation_id)
        REFERENCES stockrescue.food_safety_product_classification(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT food_safety_product_classification_revocation_actor_fk FOREIGN KEY (org_id,recorded_by_membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT,
    CONSTRAINT food_safety_product_classification_revocation_one UNIQUE (org_id,attestation_id),
    CONSTRAINT food_safety_product_classification_revocation_request_unique UNIQUE (org_id,actor_user_id,request_id)
);
CREATE TRIGGER food_safety_product_classification_revocation_immutable BEFORE UPDATE OR DELETE ON stockrescue.food_safety_product_classification_revocation
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER food_safety_product_classification_revocation_no_truncate BEFORE TRUNCATE ON stockrescue.food_safety_product_classification_revocation
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
COMMENT ON TABLE stockrescue.food_safety_product_classification_revocation IS
    'Immutable audited revocation; it never changes or deletes the signed product classification evidence.';
