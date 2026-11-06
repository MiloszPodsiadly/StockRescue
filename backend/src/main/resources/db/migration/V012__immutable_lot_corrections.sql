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
