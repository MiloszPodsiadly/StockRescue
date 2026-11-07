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
    CHECK (operation IN ('CREATE','ARCHIVE','RECLASSIFY'));
ALTER TABLE stockrescue.product_command DROP CONSTRAINT product_command_version_valid;
ALTER TABLE stockrescue.product_command ADD CONSTRAINT product_command_version_valid CHECK (
    (operation='CREATE' AND result_version=0) OR (operation IN ('ARCHIVE','RECLASSIFY') AND result_version>0));

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
