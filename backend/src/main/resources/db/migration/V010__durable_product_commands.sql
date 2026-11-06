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
