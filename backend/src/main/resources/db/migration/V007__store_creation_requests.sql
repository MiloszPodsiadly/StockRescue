CREATE TABLE stockrescue.store_creation_request (
    org_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    store_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (org_id,actor_user_id,request_id),
    UNIQUE (org_id,store_id),
    FOREIGN KEY (org_id,store_id) REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT
);
CREATE TRIGGER store_creation_request_immutable BEFORE UPDATE OR DELETE ON stockrescue.store_creation_request
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER store_creation_request_no_truncate BEFORE TRUNCATE ON stockrescue.store_creation_request
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
