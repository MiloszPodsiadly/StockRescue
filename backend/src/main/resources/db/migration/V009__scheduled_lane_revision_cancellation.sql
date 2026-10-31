ALTER TABLE stockrescue.lane_revision
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
        RAISE EXCEPTION 'Only a scheduled lane revision can be cancelled before it becomes effective' USING ERRCODE='23514';
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
    previous_revision BIGINT;
    previous_effective TIMESTAMPTZ;
    lane stockrescue.transport_lane%ROWTYPE;
BEGIN
    SELECT * INTO lane FROM stockrescue.transport_lane
        WHERE org_id=NEW.org_id AND id=NEW.lane_id FOR UPDATE;

    SELECT max(r.revision_no), max(r.effective_at) FILTER (WHERE c.id IS NULL)
        INTO previous_revision,previous_effective
        FROM stockrescue.lane_revision r
        LEFT JOIN stockrescue.lane_revision_cancellation c
          ON c.org_id=r.org_id AND c.lane_id=r.lane_id AND c.revision_id=r.id
        WHERE r.org_id=NEW.org_id AND r.lane_id=NEW.lane_id;

    IF NEW.revision_no<>coalesce(previous_revision,0)+1
        OR NEW.effective_at<transaction_timestamp()
        OR (previous_effective IS NOT NULL AND NEW.effective_at<=previous_effective) THEN
        RAISE EXCEPTION 'Lane revisions require consecutive numbers, current approval times, and increasing active effective times' USING ERRCODE='23514';
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
