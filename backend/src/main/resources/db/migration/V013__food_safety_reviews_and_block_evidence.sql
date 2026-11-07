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
