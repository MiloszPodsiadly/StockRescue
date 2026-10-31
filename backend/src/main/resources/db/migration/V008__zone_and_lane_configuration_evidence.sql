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
        REFERENCES stockrescue.storage_zone(org_id,id) ON DELETE RESTRICT,
    ADD CONSTRAINT lane_revision_org_lane_identity UNIQUE (org_id,lane_id,id),
    DROP CONSTRAINT lane_revision_lane_id_effective_at_key;

CREATE INDEX lane_revision_timeline_idx ON stockrescue.lane_revision(org_id,lane_id,effective_at,revision_no);

CREATE TABLE stockrescue.lane_revision_cancellation (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    lane_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    cancelled_by_membership_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    cancelled_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    correlation_id VARCHAR(64) CHECK (correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    UNIQUE (org_id,lane_id,revision_id),
    FOREIGN KEY (org_id,lane_id,revision_id)
        REFERENCES stockrescue.lane_revision(org_id,lane_id,id) ON DELETE RESTRICT,
    FOREIGN KEY (org_id,cancelled_by_membership_id)
        REFERENCES stockrescue.membership(org_id,id) ON DELETE RESTRICT
);
COMMENT ON TABLE stockrescue.lane_revision_cancellation IS 'Immutable evidence that a scheduled lane cost was cancelled before it became effective.';
CREATE INDEX lane_revision_cancellation_lane_idx ON stockrescue.lane_revision_cancellation(org_id,lane_id,cancelled_at);

CREATE FUNCTION stockrescue.validate_lane_revision_cancellation() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE scheduled_at TIMESTAMPTZ;
BEGIN
    PERFORM 1 FROM stockrescue.transport_lane
        WHERE org_id=NEW.org_id AND id=NEW.lane_id FOR UPDATE;

    SELECT effective_at INTO scheduled_at FROM stockrescue.lane_revision
        WHERE org_id=NEW.org_id AND lane_id=NEW.lane_id AND id=NEW.revision_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    NEW.cancelled_at := clock_timestamp();
    IF scheduled_at <= NEW.cancelled_at THEN
        RAISE EXCEPTION 'Only a future lane revision can be cancelled' USING ERRCODE='PZ001';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER lane_revision_cancellation_future
    BEFORE INSERT ON stockrescue.lane_revision_cancellation
    FOR EACH ROW EXECUTE FUNCTION stockrescue.validate_lane_revision_cancellation();
CREATE TRIGGER lane_revision_cancellation_immutable
    BEFORE UPDATE OR DELETE ON stockrescue.lane_revision_cancellation
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER lane_revision_cancellation_no_truncate
    BEFORE TRUNCATE ON stockrescue.lane_revision_cancellation
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE OR REPLACE FUNCTION stockrescue.validate_lane_revision_order() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
    previous_revision BIGINT; previous_effective TIMESTAMPTZ;
    decision_at TIMESTAMPTZ;
    lane stockrescue.transport_lane%ROWTYPE;
BEGIN
    SELECT * INTO lane FROM stockrescue.transport_lane WHERE org_id=NEW.org_id AND id=NEW.lane_id FOR UPDATE;

    decision_at := clock_timestamp();
    IF NEW.effective_at IS NULL THEN
        NEW.effective_at := decision_at;
    ELSIF NEW.effective_at < decision_at THEN
        RAISE EXCEPTION 'Lane revision effective time is in the past' USING ERRCODE='PZ002';
    END IF;
    NEW.recorded_at := decision_at;

    SELECT max(r.revision_no), max(r.effective_at) FILTER (WHERE c.id IS NULL)
        INTO previous_revision,previous_effective
        FROM stockrescue.lane_revision r
        LEFT JOIN stockrescue.lane_revision_cancellation c
          ON c.org_id=r.org_id AND c.lane_id=r.lane_id AND c.revision_id=r.id
        WHERE r.org_id=NEW.org_id AND r.lane_id=NEW.lane_id;
    IF NEW.revision_no<>coalesce(previous_revision,0)+1
        OR (previous_effective IS NOT NULL AND NEW.effective_at<=previous_effective) THEN
        RAISE EXCEPTION 'Lane revisions require consecutive numbers and increasing active effective times' USING ERRCODE='23514';
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
