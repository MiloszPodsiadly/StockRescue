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
