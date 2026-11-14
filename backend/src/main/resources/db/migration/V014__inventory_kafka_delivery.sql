ALTER TABLE stockrescue.inventory_outbox_delivery
    ADD COLUMN claim_version BIGINT NOT NULL DEFAULT 0 CHECK (claim_version>=0),
    ADD COLUMN parked_at TIMESTAMPTZ CHECK (parked_at IS NULL OR isfinite(parked_at)),
    ADD COLUMN last_error_at TIMESTAMPTZ CHECK (last_error_at IS NULL OR isfinite(last_error_at)),
    ADD CONSTRAINT outbox_delivery_parked CHECK (parked_at IS NULL OR
        (published_at IS NULL AND lease_owner IS NULL AND last_error_code IS NOT NULL));

CREATE OR REPLACE FUNCTION stockrescue.guard_inventory_delivery() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE new_claim BOOLEAN; release_claim BOOLEAN; shorten_lease BOOLEAN;
BEGIN
    IF (NEW.event_id,NEW.org_id,NEW.store_id) IS DISTINCT FROM (OLD.event_id,OLD.org_id,OLD.store_id)
        OR (OLD.published_at IS NOT NULL AND NEW IS DISTINCT FROM OLD)
        OR (OLD.parked_at IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Delivery identity, attempts, fencing and terminal state are monotonic' USING ERRCODE='23514';
    END IF;
    new_claim := NEW.claim_version=OLD.claim_version+1 AND NEW.attempt_count=OLD.attempt_count+1
        AND NEW.lease_owner IS NOT NULL AND NEW.lease_until>clock_timestamp()
        AND NEW.lease_until<=clock_timestamp()+interval '5 minutes'
        AND (OLD.lease_owner IS NULL OR OLD.lease_until<=clock_timestamp())
        AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
        AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
        AND NEW.parked_at IS NOT DISTINCT FROM OLD.parked_at
        AND NEW.last_error_code IS NOT DISTINCT FROM OLD.last_error_code
        AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at;
    release_claim := NEW.claim_version=OLD.claim_version AND NEW.attempt_count=OLD.attempt_count
        AND OLD.lease_owner IS NOT NULL AND OLD.lease_until>clock_timestamp()
        AND NEW.lease_owner IS NULL AND NEW.lease_until IS NULL
        AND (
            (OLD.published_at IS NULL AND NEW.published_at IS NOT NULL AND NEW.parked_at IS NULL
                AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
                AND NEW.last_error_code IS NULL AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at)
            OR (OLD.parked_at IS NULL AND NEW.parked_at IS NOT NULL AND NEW.published_at IS NULL
                AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
                AND NEW.last_error_code IS NOT NULL AND NEW.last_error_at IS NOT NULL
                AND (OLD.last_error_at IS NULL OR NEW.last_error_at>=OLD.last_error_at))
            OR (NEW.published_at IS NULL AND NEW.parked_at IS NULL AND NEW.next_attempt_at>OLD.next_attempt_at
                AND NEW.last_error_code IS NOT NULL AND NEW.last_error_at IS NOT NULL
                AND (OLD.last_error_at IS NULL OR NEW.last_error_at>=OLD.last_error_at))
        );
    shorten_lease := NEW.claim_version=OLD.claim_version AND NEW.attempt_count=OLD.attempt_count
        AND OLD.lease_owner IS NOT NULL AND NEW.lease_owner=OLD.lease_owner
        AND NEW.lease_until IS NOT NULL AND NEW.lease_until<OLD.lease_until
        AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
        AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
        AND NEW.parked_at IS NOT DISTINCT FROM OLD.parked_at
        AND NEW.last_error_code IS NOT DISTINCT FROM OLD.last_error_code
        AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at;
    IF NEW.claim_version=OLD.claim_version+1 THEN
        IF NOT new_claim THEN
            RAISE EXCEPTION 'A delivery claim requires a fresh fencing version and lease' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.claim_version=OLD.claim_version THEN
        IF (NEW.attempt_count,NEW.lease_owner,NEW.lease_until,NEW.next_attempt_at,NEW.published_at,
                NEW.parked_at,NEW.last_error_code,NEW.last_error_at)
                IS DISTINCT FROM (OLD.attempt_count,OLD.lease_owner,OLD.lease_until,OLD.next_attempt_at,OLD.published_at,
                    OLD.parked_at,OLD.last_error_code,OLD.last_error_at)
            AND NOT release_claim AND NOT shorten_lease THEN
            RAISE EXCEPTION 'Only the current unexpired lease may publish, park or schedule a retry' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'A delivery fencing version advances by exactly one claim' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE INDEX inventory_outbox_claim_idx ON stockrescue.inventory_outbox_delivery(next_attempt_at,lease_until,event_id)
    WHERE published_at IS NULL AND parked_at IS NULL;

ALTER TABLE stockrescue.inventory_inbox
    ADD COLUMN processing_outcome VARCHAR(16) NOT NULL DEFAULT 'APPLIED'
        CHECK (processing_outcome IN ('APPLIED','STALE'));

CREATE TABLE stockrescue.inventory_change_projection (
    consumer_name VARCHAR(64) NOT NULL CHECK (consumer_name ~ '^[a-z][a-z0-9.-]{0,63}$'),
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    position_id UUID NOT NULL,
    event_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version>0),
    change JSONB NOT NULL CHECK (jsonb_typeof(change)='object'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(updated_at)),
    PRIMARY KEY (consumer_name,org_id,store_id,position_id),
    CONSTRAINT inventory_change_projection_event_fk FOREIGN KEY
        (consumer_name,event_id,org_id,store_id,position_id,aggregate_version)
        REFERENCES stockrescue.inventory_inbox(consumer_name,event_id,org_id,store_id,position_id,aggregate_version) ON DELETE RESTRICT,
    CONSTRAINT inventory_change_projection_position_fk FOREIGN KEY (org_id,store_id,position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT
);
CREATE INDEX inventory_change_projection_lag_idx ON stockrescue.inventory_change_projection(consumer_name,updated_at);

CREATE FUNCTION stockrescue.guard_inventory_change_projection() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.consumer_name,NEW.org_id,NEW.store_id,NEW.position_id) IS DISTINCT FROM
        (OLD.consumer_name,OLD.org_id,OLD.store_id,OLD.position_id) OR NEW.aggregate_version<=OLD.aggregate_version THEN
        RAISE EXCEPTION 'Derived inventory projections advance monotonically within their scope' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inventory_change_projection_guard BEFORE UPDATE ON stockrescue.inventory_change_projection
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_inventory_change_projection();
CREATE TRIGGER inventory_change_projection_no_delete BEFORE DELETE ON stockrescue.inventory_change_projection
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER inventory_change_projection_no_truncate BEFORE TRUNCATE ON stockrescue.inventory_change_projection
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

CREATE TABLE stockrescue.inventory_consumer_parked_event (
    consumer_name VARCHAR(64) NOT NULL CHECK (consumer_name ~ '^[a-z][a-z0-9.-]{0,63}$'),
    topic VARCHAR(249) NOT NULL CHECK (length(topic)>0),
    partition_id INTEGER NOT NULL CHECK (partition_id>=0),
    record_offset BIGINT NOT NULL CHECK (record_offset>=0),
    event_id UUID,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    failure_code VARCHAR(64) NOT NULL CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    occurrences INTEGER NOT NULL DEFAULT 1 CHECK (occurrences>0),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(first_seen_at)),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(last_seen_at) AND last_seen_at>=first_seen_at),
    PRIMARY KEY (consumer_name,topic,partition_id,record_offset)
);
CREATE INDEX inventory_consumer_parked_page_idx ON stockrescue.inventory_consumer_parked_event(consumer_name,last_seen_at DESC);
CREATE FUNCTION stockrescue.guard_inventory_parked_event() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.consumer_name,NEW.topic,NEW.partition_id,NEW.record_offset,NEW.event_id,NEW.payload_digest,NEW.failure_code,NEW.first_seen_at)
        IS DISTINCT FROM (OLD.consumer_name,OLD.topic,OLD.partition_id,OLD.record_offset,OLD.event_id,OLD.payload_digest,OLD.failure_code,OLD.first_seen_at)
        OR NEW.occurrences<OLD.occurrences OR NEW.last_seen_at<OLD.last_seen_at THEN
        RAISE EXCEPTION 'Parked event identity is immutable and observations are monotonic' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inventory_consumer_parked_guard BEFORE UPDATE ON stockrescue.inventory_consumer_parked_event
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_inventory_parked_event();
CREATE TRIGGER inventory_consumer_parked_no_delete BEFORE DELETE ON stockrescue.inventory_consumer_parked_event
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER inventory_consumer_parked_no_truncate BEFORE TRUNCATE ON stockrescue.inventory_consumer_parked_event
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

COMMENT ON TABLE stockrescue.inventory_change_projection IS 'Monotonic, derived consumer view. It is never an authoritative stock balance.';
COMMENT ON TABLE stockrescue.inventory_consumer_parked_event IS 'Payload-free durable record of malformed or unsupported topic records that were parked before the consumer offset advanced.';
