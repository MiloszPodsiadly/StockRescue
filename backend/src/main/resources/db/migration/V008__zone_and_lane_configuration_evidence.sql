ALTER TABLE stockrescue.storage_zone
    ADD COLUMN name_normalized VARCHAR(100) GENERATED ALWAYS AS (lower(btrim(name) COLLATE "C")) STORED,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT storage_zone_name_normalized_unique UNIQUE (org_id,store_id,name_normalized),
    ADD CONSTRAINT storage_zone_store_identity UNIQUE (org_id,store_id,id),
    ADD CONSTRAINT storage_zone_updated_after_created CHECK (updated_at>=created_at);

CREATE FUNCTION stockrescue.validate_storage_zone_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.id,NEW.org_id,NEW.store_id,NEW.temperature_class,NEW.created_at)
        IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.store_id,OLD.temperature_class,OLD.created_at)
        OR (NOT OLD.active AND NEW.active) THEN
        RAISE EXCEPTION 'Zone identity and temperature class are fixed; deactivation is permanent' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER storage_zone_identity BEFORE UPDATE ON stockrescue.storage_zone
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_storage_zone_update();

ALTER TABLE stockrescue.transport_lane
    ADD COLUMN origin_zone_id UUID,
    ADD COLUMN destination_zone_id UUID,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT lane_zone_pair CHECK ((origin_zone_id IS NULL)=(destination_zone_id IS NULL)),
    ADD CONSTRAINT lane_origin_zone_fk FOREIGN KEY (org_id,origin_store_id,origin_zone_id)
        REFERENCES stockrescue.storage_zone(org_id,store_id,id) ON DELETE RESTRICT,
    ADD CONSTRAINT lane_destination_zone_fk FOREIGN KEY (org_id,destination_store_id,destination_zone_id)
        REFERENCES stockrescue.storage_zone(org_id,store_id,id) ON DELETE RESTRICT,
    ADD CONSTRAINT lane_updated_after_created CHECK (updated_at>=created_at);

CREATE FUNCTION stockrescue.validate_lane_identity() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.id,NEW.org_id,NEW.origin_store_id,NEW.destination_store_id,NEW.created_at)
        IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.origin_store_id,OLD.destination_store_id,OLD.created_at) THEN
        RAISE EXCEPTION 'Lane endpoints and identity are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transport_lane_identity BEFORE UPDATE ON stockrescue.transport_lane
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_lane_identity();

ALTER TABLE stockrescue.lane_revision
    ADD COLUMN origin_zone_id UUID,
    ADD COLUMN destination_zone_id UUID,
    ADD COLUMN temperature_class VARCHAR(16),
    ADD CONSTRAINT lane_revision_zone_evidence CHECK (
        (origin_zone_id IS NULL AND destination_zone_id IS NULL AND temperature_class IS NULL)
        OR (origin_zone_id IS NOT NULL AND destination_zone_id IS NOT NULL AND temperature_class IS NOT NULL AND temperature_class IN ('AMBIENT','CHILLED','FROZEN'))
    ),
    ADD CONSTRAINT revision_origin_zone_fk FOREIGN KEY (org_id,origin_zone_id)
        REFERENCES stockrescue.storage_zone(org_id,id) ON DELETE RESTRICT,
    ADD CONSTRAINT revision_destination_zone_fk FOREIGN KEY (org_id,destination_zone_id)
        REFERENCES stockrescue.storage_zone(org_id,id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION stockrescue.validate_lane_revision_order() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
    previous_revision BIGINT; previous_effective TIMESTAMPTZ;
    lane stockrescue.transport_lane%ROWTYPE;
BEGIN
    SELECT * INTO lane FROM stockrescue.transport_lane WHERE org_id=NEW.org_id AND id=NEW.lane_id FOR UPDATE;
    SELECT revision_no,effective_at INTO previous_revision,previous_effective
        FROM stockrescue.lane_revision WHERE org_id=NEW.org_id AND lane_id=NEW.lane_id
        ORDER BY revision_no DESC LIMIT 1;
    IF NEW.revision_no<>coalesce(previous_revision,0)+1
        OR (previous_effective IS NOT NULL AND NEW.effective_at<=previous_effective) THEN
        RAISE EXCEPTION 'Lane revisions require consecutive numbers and increasing effective times' USING ERRCODE='23514';
    END IF;
    -- Older unconfigured lanes retain their unknown zone evidence. New configured
    -- lanes must snapshot the exact current, compatible active zone pair.
    IF (NEW.origin_zone_id,NEW.destination_zone_id) IS DISTINCT FROM (lane.origin_zone_id,lane.destination_zone_id) THEN
        RAISE EXCEPTION 'Revision zone evidence must match the lane' USING ERRCODE='23514';
    END IF;
    IF NEW.origin_zone_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM stockrescue.storage_zone a JOIN stockrescue.storage_zone b ON b.org_id=a.org_id
        WHERE a.org_id=NEW.org_id AND a.store_id=lane.origin_store_id AND a.id=NEW.origin_zone_id
          AND b.store_id=lane.destination_store_id AND b.id=NEW.destination_zone_id
          AND a.active AND b.active AND a.temperature_class=b.temperature_class AND a.temperature_class=NEW.temperature_class
    ) THEN
        RAISE EXCEPTION 'Revision requires compatible active zones' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
