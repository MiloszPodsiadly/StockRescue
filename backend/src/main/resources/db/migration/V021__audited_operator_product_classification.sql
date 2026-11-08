ALTER TABLE stockrescue.product_command DROP CONSTRAINT product_command_operation_check;
ALTER TABLE stockrescue.product_command ALTER COLUMN operation TYPE VARCHAR(32);
ALTER TABLE stockrescue.product_command ADD CONSTRAINT product_command_operation_check
    CHECK (operation IN ('CREATE','ARCHIVE','RECLASSIFY','CONFIRM_CLASSIFICATION'));
ALTER TABLE stockrescue.product_command DROP CONSTRAINT product_command_version_valid;
ALTER TABLE stockrescue.product_command ADD CONSTRAINT product_command_version_valid CHECK (
    (operation='CREATE' AND result_version=0)
    OR (operation IN ('ARCHIVE','RECLASSIFY','CONFIRM_CLASSIFICATION') AND result_version>0));

CREATE TABLE stockrescue.food_safety_product_classification_confirmation (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    product_id UUID NOT NULL,
    sku VARCHAR(64) COLLATE "C" NOT NULL,
    expected_product_version BIGINT NOT NULL CHECK (expected_product_version>=0),
    product_version BIGINT NOT NULL CHECK (product_version>0),
    original_category VARCHAR(24) NOT NULL CHECK (original_category IN ('UNCLASSIFIED','DAIRY','BAKERY')),
    confirmed_category VARCHAR(24) NOT NULL CHECK (confirmed_category IN ('UNCLASSIFIED','DAIRY','BAKERY')),
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
    CONSTRAINT food_safety_product_classification_confirmation_known_category_has_source CHECK (
        confirmed_category='UNCLASSIFIED' OR source_type IS NOT NULL),
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
    'Immutable operator-confirmed descriptive category with cited evidence, actor, reason, durable request and exact resulting product version; never independent or specialist verification.';
