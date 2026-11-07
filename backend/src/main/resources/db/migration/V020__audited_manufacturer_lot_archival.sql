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
