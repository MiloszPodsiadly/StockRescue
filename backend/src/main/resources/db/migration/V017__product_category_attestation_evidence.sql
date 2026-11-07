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
