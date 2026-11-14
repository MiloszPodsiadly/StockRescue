ALTER TABLE stockrescue.manufacturer_lot ADD CONSTRAINT lot_inventory_identity UNIQUE (org_id,product_id,id);

CREATE TABLE stockrescue.stock_position (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    location_id UUID NOT NULL,
    product_id UUID NOT NULL,
    lot_id UUID NOT NULL,
    unit VARCHAR(8) NOT NULL DEFAULT 'EA' CHECK (unit='EA'),
    initialization_state VARCHAR(24) NOT NULL DEFAULT 'NOT_INITIALIZED'
        CHECK (initialization_state IN ('NOT_INITIALIZED','INITIALIZED')),
    trust_state VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED' CHECK (trust_state IN ('UNVERIFIED','CLEAN','FROZEN')),
    on_hand BIGINT NOT NULL DEFAULT 0 CHECK (on_hand>=0),
    reserved BIGINT NOT NULL DEFAULT 0 CHECK (reserved>=0),
    quarantined BIGINT NOT NULL DEFAULT 0 CHECK (quarantined>=0),
    other_blocked BIGINT NOT NULL DEFAULT 0 CHECK (other_blocked>=0),
    available BIGINT GENERATED ALWAYS AS (on_hand-reserved-quarantined-other_blocked) STORED,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(created_at)),
    CONSTRAINT stock_position_buckets_valid CHECK (reserved::numeric+quarantined+other_blocked<=on_hand),
    CONSTRAINT stock_position_unknown_empty CHECK (initialization_state='INITIALIZED'
        OR (on_hand=0 AND reserved=0 AND quarantined=0 AND other_blocked=0)),
    CONSTRAINT stock_position_initialization_version CHECK ((initialization_state='NOT_INITIALIZED')=(version=0)),
    CONSTRAINT stock_position_identity UNIQUE (org_id,store_id,location_id,lot_id),
    CONSTRAINT stock_position_scope UNIQUE (org_id,store_id,id),
    CONSTRAINT stock_position_location_fk FOREIGN KEY (org_id,store_id,location_id)
        REFERENCES stockrescue.storage_location(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT stock_position_lot_fk FOREIGN KEY (org_id,product_id,lot_id)
        REFERENCES stockrescue.manufacturer_lot(org_id,product_id,id) ON DELETE RESTRICT
);
CREATE INDEX stock_position_product_page_idx ON stockrescue.stock_position(org_id,store_id,product_id,lot_id,location_id,id);

CREATE FUNCTION stockrescue.guard_stock_position() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.version<>0 OR NEW.initialization_state<>'NOT_INITIALIZED' OR NEW.trust_state<>'UNVERIFIED'
            OR NEW.on_hand<>0 OR NEW.reserved<>0 OR NEW.quarantined<>0 OR NEW.other_blocked<>0 THEN
            RAISE EXCEPTION 'A new position requires uninitialized zero' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (NEW.id,NEW.org_id,NEW.store_id,NEW.location_id,NEW.product_id,NEW.lot_id,NEW.unit,NEW.created_at)
            IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.store_id,OLD.location_id,OLD.product_id,OLD.lot_id,OLD.unit,OLD.created_at)
            OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1
            OR (OLD.initialization_state='INITIALIZED' AND NEW.initialization_state<>'INITIALIZED') THEN
            RAISE EXCEPTION 'Position identity and monotonic version are required' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM stockrescue.stock_movement_line l
            WHERE l.org_id=NEW.org_id AND l.store_id=NEW.store_id AND l.position_id=NEW.id AND l.position_version=NEW.version
              AND OLD.on_hand::numeric+l.delta_on_hand=NEW.on_hand
              AND OLD.reserved::numeric+l.delta_reserved=NEW.reserved
              AND OLD.quarantined::numeric+l.delta_quarantined=NEW.quarantined
              AND OLD.other_blocked::numeric+l.delta_other_blocked=NEW.other_blocked
              AND l.initialization_after=NEW.initialization_state AND l.trust_after=NEW.trust_state) THEN
            RAISE EXCEPTION 'A projection update requires matching immutable movement evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER stock_position_guard BEFORE INSERT OR UPDATE ON stockrescue.stock_position
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_stock_position();

CREATE TABLE stockrescue.inventory_command_receipt (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    operation VARCHAR(24) NOT NULL CHECK (operation IN
        ('RECEIVE','RESERVE','RELEASE','ADJUST','WRITE_OFF','QUARANTINE','HOLD','RELEASE_HOLD','SALE','VERIFY_ZERO','RECONCILE','REMEDIATE')),
    request_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    outcome JSONB NOT NULL CHECK (jsonb_typeof(outcome)='object'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT inventory_command_request_unique UNIQUE (org_id,actor_user_id,operation,request_id),
    CONSTRAINT inventory_command_scope UNIQUE (org_id,store_id,id,operation),
    CONSTRAINT inventory_command_store_fk FOREIGN KEY (org_id,store_id) REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT inventory_command_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT
);

CREATE TABLE stockrescue.stock_movement (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    command_id UUID NOT NULL,
    operation VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    evidence_reference VARCHAR(256) NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference COLLATE "C" !~ '[[:cntrl:]]'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT stock_movement_command_unique UNIQUE (org_id,store_id,command_id),
    CONSTRAINT stock_movement_scope UNIQUE (org_id,store_id,id),
    CONSTRAINT stock_movement_operation_scope UNIQUE (org_id,store_id,id,operation),
    CONSTRAINT stock_movement_command_fk FOREIGN KEY (org_id,store_id,command_id,operation)
        REFERENCES stockrescue.inventory_command_receipt(org_id,store_id,id,operation) ON DELETE RESTRICT
);
CREATE INDEX stock_movement_history_idx ON stockrescue.stock_movement(org_id,store_id,recorded_at,id);

CREATE TABLE stockrescue.stock_movement_line (
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    movement_id UUID NOT NULL,
    position_id UUID NOT NULL,
    position_version BIGINT NOT NULL CHECK (position_version>0),
    delta_on_hand BIGINT NOT NULL CHECK (delta_on_hand<>-9223372036854775808),
    delta_reserved BIGINT NOT NULL CHECK (delta_reserved<>-9223372036854775808),
    delta_quarantined BIGINT NOT NULL CHECK (delta_quarantined<>-9223372036854775808),
    delta_other_blocked BIGINT NOT NULL CHECK (delta_other_blocked<>-9223372036854775808),
    initialization_after VARCHAR(24) NOT NULL CHECK (initialization_after='INITIALIZED'),
    trust_after VARCHAR(16) NOT NULL CHECK (trust_after IN ('UNVERIFIED','CLEAN','FROZEN')),
    PRIMARY KEY (org_id,store_id,movement_id,position_id),
    CONSTRAINT stock_line_version_unique UNIQUE (org_id,store_id,position_id,position_version),
    CONSTRAINT stock_line_event_identity UNIQUE (org_id,store_id,movement_id,position_id,position_version),
    CONSTRAINT stock_line_movement_fk FOREIGN KEY (org_id,store_id,movement_id)
        REFERENCES stockrescue.stock_movement(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT stock_line_position_fk FOREIGN KEY (org_id,store_id,position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT
);

CREATE TABLE stockrescue.goods_receipt (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    movement_id UUID NOT NULL,
    operation VARCHAR(24) NOT NULL DEFAULT 'RECEIVE' CHECK (operation='RECEIVE'),
    supplier_key VARCHAR(200) COLLATE "C" NOT NULL CHECK (supplier_key=btrim(supplier_key) AND length(supplier_key)>0 AND supplier_key !~ '[[:cntrl:]]'),
    document_type VARCHAR(32) COLLATE "C" NOT NULL CHECK (document_type=btrim(document_type) AND length(document_type)>0 AND document_type !~ '[[:cntrl:]]'),
    document_reference VARCHAR(200) COLLATE "C" NOT NULL CHECK (document_reference=btrim(document_reference) AND length(document_reference)>0 AND document_reference !~ '[[:cntrl:]]'),
    physical_receipt_at TIMESTAMPTZ NOT NULL CHECK (isfinite(physical_receipt_at)),
    CONSTRAINT goods_receipt_document_unique UNIQUE (org_id,supplier_key,document_type,document_reference),
    CONSTRAINT goods_receipt_scope UNIQUE (org_id,store_id,id,movement_id),
    CONSTRAINT goods_receipt_movement_unique UNIQUE (org_id,store_id,movement_id),
    CONSTRAINT goods_receipt_movement_fk FOREIGN KEY (org_id,store_id,movement_id,operation)
        REFERENCES stockrescue.stock_movement(org_id,store_id,id,operation) ON DELETE RESTRICT
);
CREATE TABLE stockrescue.goods_receipt_line (
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    receipt_id UUID NOT NULL,
    movement_id UUID NOT NULL,
    position_id UUID NOT NULL,
    document_line_reference VARCHAR(64) COLLATE "C" NOT NULL CHECK (document_line_reference=btrim(document_line_reference)
        AND length(document_line_reference)>0 AND document_line_reference !~ '[[:cntrl:]]'),
    quantity BIGINT NOT NULL CHECK (quantity>0),
    disposition VARCHAR(16) NOT NULL CHECK (disposition IN ('AVAILABLE','QUARANTINED','OTHER_BLOCKED')),
    PRIMARY KEY (org_id,receipt_id,document_line_reference),
    CONSTRAINT receipt_line_receipt_fk FOREIGN KEY (org_id,store_id,receipt_id,movement_id)
        REFERENCES stockrescue.goods_receipt(org_id,store_id,id,movement_id) ON DELETE RESTRICT,
    CONSTRAINT receipt_line_movement_fk FOREIGN KEY (org_id,store_id,movement_id,position_id)
        REFERENCES stockrescue.stock_movement_line(org_id,store_id,movement_id,position_id) ON DELETE RESTRICT
);

CREATE TABLE stockrescue.stock_allocation (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    position_id UUID NOT NULL,
    bucket VARCHAR(16) NOT NULL CHECK (bucket IN ('RESERVED','QUARANTINED','OTHER_BLOCKED')),
    owner_user_id UUID NOT NULL,
    owner_membership_id UUID NOT NULL,
    initial_quantity BIGINT NOT NULL CHECK (initial_quantity>0),
    remaining_quantity BIGINT NOT NULL CHECK (remaining_quantity>=0 AND remaining_quantity<=initial_quantity),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','RELEASED','CONSUMED','WRITTEN_OFF')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
    CONSTRAINT stock_allocation_state_valid CHECK ((state='ACTIVE')=(remaining_quantity>0)),
    CONSTRAINT stock_allocation_scope UNIQUE (org_id,store_id,id,position_id,bucket),
    CONSTRAINT stock_allocation_position_fk FOREIGN KEY (org_id,store_id,position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT stock_allocation_owner_fk FOREIGN KEY (org_id,owner_membership_id,owner_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT
);
CREATE INDEX stock_allocation_active_idx ON stockrescue.stock_allocation(org_id,store_id,position_id,bucket,id) WHERE state='ACTIVE';
CREATE TABLE stockrescue.stock_allocation_transition (
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    allocation_id UUID NOT NULL,
    position_id UUID NOT NULL,
    bucket VARCHAR(16) NOT NULL,
    movement_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version>=0),
    previous_version BIGINT GENERATED ALWAYS AS (CASE WHEN version>0 THEN version-1 ELSE NULL END) STORED,
    remaining_before BIGINT NOT NULL CHECK (remaining_before>=0),
    remaining_after BIGINT NOT NULL CHECK (remaining_after>=0),
    state_after VARCHAR(16) NOT NULL CHECK (state_after IN ('ACTIVE','RELEASED','CONSUMED','WRITTEN_OFF')),
    transition VARCHAR(16) NOT NULL CHECK (transition IN ('CREATE','RELEASE','CONSUME','WRITE_OFF')),
    PRIMARY KEY (org_id,store_id,allocation_id,version),
    CONSTRAINT allocation_transition_state CHECK ((state_after='ACTIVE')=(remaining_after>0)),
    CONSTRAINT allocation_transition_amount CHECK (
        (version=0 AND transition='CREATE' AND remaining_before=0 AND remaining_after>0 AND state_after='ACTIVE')
        OR (version>0 AND transition<>'CREATE' AND remaining_after<remaining_before)),
    CONSTRAINT allocation_transition_result UNIQUE (org_id,store_id,allocation_id,version,remaining_after,state_after),
    CONSTRAINT allocation_transition_quantity UNIQUE (org_id,store_id,allocation_id,version,remaining_after),
    CONSTRAINT allocation_transition_previous_fk FOREIGN KEY (org_id,store_id,allocation_id,previous_version,remaining_before)
        REFERENCES stockrescue.stock_allocation_transition(org_id,store_id,allocation_id,version,remaining_after) ON DELETE RESTRICT,
    CONSTRAINT allocation_transition_closure CHECK (remaining_after>0
        OR (transition='RELEASE' AND state_after='RELEASED') OR (transition='CONSUME' AND state_after='CONSUMED')
        OR (transition='WRITE_OFF' AND state_after='WRITTEN_OFF')),
    CONSTRAINT allocation_transition_allocation_fk FOREIGN KEY (org_id,store_id,allocation_id,position_id,bucket)
        REFERENCES stockrescue.stock_allocation(org_id,store_id,id,position_id,bucket) ON DELETE RESTRICT,
    CONSTRAINT allocation_transition_movement_fk FOREIGN KEY (org_id,store_id,movement_id,position_id)
        REFERENCES stockrescue.stock_movement_line(org_id,store_id,movement_id,position_id) ON DELETE RESTRICT
);
ALTER TABLE stockrescue.stock_allocation ADD CONSTRAINT stock_allocation_current_evidence_fk
    FOREIGN KEY (org_id,store_id,id,version,remaining_quantity,state)
    REFERENCES stockrescue.stock_allocation_transition(org_id,store_id,allocation_id,version,remaining_after,state_after)
    DEFERRABLE INITIALLY DEFERRED;
CREATE FUNCTION stockrescue.guard_stock_allocation() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.version<>0 OR NEW.state<>'ACTIVE' OR NEW.remaining_quantity<>NEW.initial_quantity THEN
            RAISE EXCEPTION 'Allocation must start active at its full quantity' USING ERRCODE='23514';
        END IF;
    ELSIF (NEW.id,NEW.org_id,NEW.store_id,NEW.position_id,NEW.bucket,NEW.owner_user_id,NEW.owner_membership_id,NEW.initial_quantity)
        IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.store_id,OLD.position_id,OLD.bucket,OLD.owner_user_id,OLD.owner_membership_id,OLD.initial_quantity)
        OR OLD.state<>'ACTIVE' OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1
        OR NEW.remaining_quantity>=OLD.remaining_quantity THEN
        RAISE EXCEPTION 'Allocation identity and one-way lifecycle are required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER stock_allocation_guard BEFORE INSERT OR UPDATE ON stockrescue.stock_allocation
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_stock_allocation();

CREATE FUNCTION stockrescue.check_inventory_line_evidence() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE line record; ownership record;
BEGIN
    SELECT l.* INTO STRICT line FROM stockrescue.stock_movement_line l
        WHERE l.org_id=NEW.org_id AND l.store_id=NEW.store_id AND l.movement_id=NEW.movement_id AND l.position_id=NEW.position_id;
    IF NOT EXISTS (SELECT 1 FROM stockrescue.stock_position p WHERE p.org_id=line.org_id AND p.store_id=line.store_id
        AND p.id=line.position_id AND p.version>=line.position_version) THEN
        RAISE EXCEPTION 'Movement evidence must have a committed projection' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM stockrescue.inventory_outbox_event e
        JOIN stockrescue.inventory_outbox_delivery d ON d.event_id=e.id AND d.org_id=e.org_id AND d.store_id=e.store_id
        WHERE e.org_id=line.org_id AND e.store_id=line.store_id AND e.position_id=line.position_id
            AND e.aggregate_version=line.position_version AND e.movement_id=line.movement_id) THEN
        RAISE EXCEPTION 'Movement evidence requires a durable pending event' USING ERRCODE='23514';
    END IF;
    SELECT coalesce(sum(remaining_after::numeric-remaining_before) FILTER (WHERE bucket='RESERVED'),0) AS reserved,
        coalesce(sum(remaining_after::numeric-remaining_before) FILTER (WHERE bucket='QUARANTINED'),0) AS quarantined,
        coalesce(sum(remaining_after::numeric-remaining_before) FILTER (WHERE bucket='OTHER_BLOCKED'),0) AS blocked
        INTO ownership FROM stockrescue.stock_allocation_transition
        WHERE org_id=line.org_id AND store_id=line.store_id AND movement_id=line.movement_id AND position_id=line.position_id;
    IF (ownership.reserved,ownership.quarantined,ownership.blocked)
        IS DISTINCT FROM (line.delta_reserved::numeric,line.delta_quarantined::numeric,line.delta_other_blocked::numeric) THEN
        RAISE EXCEPTION 'Bucket changes require matching disjoint allocation evidence' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='stock_allocation_transition' THEN
        IF NOT EXISTS (SELECT 1 FROM stockrescue.stock_allocation a
            WHERE a.org_id=NEW.org_id AND a.store_id=NEW.store_id AND a.id=NEW.allocation_id AND a.version>=NEW.version
                AND (NEW.version>0 OR NEW.remaining_after=a.initial_quantity)) THEN
            RAISE EXCEPTION 'Allocation transition requires matching current ownership' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER stock_line_evidence AFTER INSERT ON stockrescue.stock_movement_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION stockrescue.check_inventory_line_evidence();
CREATE CONSTRAINT TRIGGER allocation_line_evidence AFTER INSERT ON stockrescue.stock_allocation_transition
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION stockrescue.check_inventory_line_evidence();

CREATE TABLE stockrescue.inventory_reconciliation_run (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    severity VARCHAR(8) NOT NULL CHECK (severity IN ('CLEAN','WARN','CRITICAL')),
    checked_positions INTEGER NOT NULL CHECK (checked_positions>=0),
    started_at TIMESTAMPTZ NOT NULL CHECK (isfinite(started_at)),
    completed_at TIMESTAMPTZ NOT NULL CHECK (isfinite(completed_at) AND completed_at>=started_at),
    CONSTRAINT reconciliation_run_scope UNIQUE (org_id,store_id,id),
    CONSTRAINT reconciliation_run_store_fk FOREIGN KEY (org_id,store_id) REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT reconciliation_run_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT
);
CREATE TABLE stockrescue.inventory_reconciliation_result (
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    run_id UUID NOT NULL,
    position_id UUID NOT NULL,
    position_version BIGINT NOT NULL CHECK (position_version>=0),
    severity VARCHAR(8) NOT NULL CHECK (severity IN ('CLEAN','WARN','CRITICAL')),
    finding_code VARCHAR(64) NOT NULL CHECK (finding_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    expected_buckets JSONB NOT NULL CHECK (jsonb_typeof(expected_buckets)='object'),
    observed_buckets JSONB NOT NULL CHECK (jsonb_typeof(observed_buckets)='object'),
    PRIMARY KEY (org_id,store_id,run_id,position_id),
    CONSTRAINT reconciliation_result_run_fk FOREIGN KEY (org_id,store_id,run_id)
        REFERENCES stockrescue.inventory_reconciliation_run(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT reconciliation_result_position_fk FOREIGN KEY (org_id,store_id,position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT
);

CREATE TABLE stockrescue.inventory_outbox_event (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    movement_id UUID NOT NULL,
    position_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version>0),
    event_type VARCHAR(32) NOT NULL CHECK (event_type='InventoryStockChanged'),
    schema_version INTEGER NOT NULL CHECK (schema_version=1),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='object'),
    correlation_id VARCHAR(64),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT inventory_outbox_scope UNIQUE (org_id,store_id,id),
    CONSTRAINT inventory_outbox_consumer_identity UNIQUE (org_id,store_id,id,position_id,aggregate_version),
    CONSTRAINT inventory_outbox_envelope CHECK (payload ?& ARRAY['eventId','schemaVersion','eventType','organizationId','storeId','positionId','aggregateVersion','movementId','occurredAt','change','correlationId']
        AND coalesce(payload->>'eventId'=id::text AND payload->>'schemaVersion'='1' AND payload->>'eventType'=event_type
        AND payload->>'organizationId'=org_id::text AND payload->>'storeId'=store_id::text
        AND payload->>'positionId'=position_id::text AND payload->>'aggregateVersion'=aggregate_version::text
        AND payload->>'movementId'=movement_id::text AND jsonb_typeof(payload->'schemaVersion')='number'
        AND jsonb_typeof(payload->'aggregateVersion')='number' AND jsonb_typeof(payload->'change')='object'
        AND jsonb_typeof(payload->'occurredAt')='string',FALSE)
        AND (payload->>'correlationId') IS NOT DISTINCT FROM correlation_id),
    CONSTRAINT inventory_outbox_aggregate_unique UNIQUE (org_id,store_id,position_id,aggregate_version),
    CONSTRAINT inventory_outbox_line_fk FOREIGN KEY (org_id,store_id,movement_id,position_id,aggregate_version)
        REFERENCES stockrescue.stock_movement_line(org_id,store_id,movement_id,position_id,position_version) ON DELETE RESTRICT
);
CREATE TABLE stockrescue.inventory_outbox_delivery (
    event_id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(next_attempt_at)),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ CHECK (published_at IS NULL OR isfinite(published_at)),
    last_error_code VARCHAR(64) CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT outbox_delivery_lease CHECK ((lease_owner IS NULL)=(lease_until IS NULL) AND (lease_until IS NULL OR isfinite(lease_until))),
    CONSTRAINT outbox_delivery_published CHECK (published_at IS NULL OR lease_owner IS NULL),
    CONSTRAINT outbox_delivery_event_fk FOREIGN KEY (org_id,store_id,event_id)
        REFERENCES stockrescue.inventory_outbox_event(org_id,store_id,id) ON DELETE RESTRICT
);
CREATE INDEX inventory_outbox_pending_idx ON stockrescue.inventory_outbox_delivery(next_attempt_at,event_id) WHERE published_at IS NULL;
CREATE FUNCTION stockrescue.guard_inventory_delivery() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF (NEW.event_id,NEW.org_id,NEW.store_id) IS DISTINCT FROM (OLD.event_id,OLD.org_id,OLD.store_id)
        OR NEW.attempt_count<OLD.attempt_count OR (OLD.published_at IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Delivery identity, attempts and publication are monotonic' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inventory_delivery_guard BEFORE UPDATE ON stockrescue.inventory_outbox_delivery
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_inventory_delivery();

CREATE TABLE stockrescue.inventory_inbox (
    consumer_name VARCHAR(64) NOT NULL CHECK (consumer_name ~ '^[a-z][a-z0-9.-]{0,63}$'),
    event_id UUID NOT NULL,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    position_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version>0),
    payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest)=32),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(applied_at)),
    PRIMARY KEY (consumer_name,event_id),
    CONSTRAINT inventory_inbox_aggregate_unique UNIQUE (consumer_name,org_id,store_id,position_id,aggregate_version),
    CONSTRAINT inventory_inbox_scope UNIQUE (consumer_name,event_id,org_id,store_id,position_id,aggregate_version),
    CONSTRAINT inventory_inbox_position_fk FOREIGN KEY (org_id,store_id,position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT inventory_inbox_event_fk FOREIGN KEY (org_id,store_id,event_id,position_id,aggregate_version)
        REFERENCES stockrescue.inventory_outbox_event(org_id,store_id,id,position_id,aggregate_version) ON DELETE RESTRICT
);
CREATE TABLE stockrescue.inventory_change_feed (
    consumer_name VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    position_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    change JSONB NOT NULL CHECK (jsonb_typeof(change)='object'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    PRIMARY KEY (consumer_name,event_id),
    CONSTRAINT inventory_feed_inbox_fk FOREIGN KEY (consumer_name,event_id,org_id,store_id,position_id,aggregate_version)
        REFERENCES stockrescue.inventory_inbox(consumer_name,event_id,org_id,store_id,position_id,aggregate_version) ON DELETE RESTRICT
);
CREATE INDEX inventory_change_feed_page_idx ON stockrescue.inventory_change_feed(org_id,store_id,recorded_at,event_id);

CREATE FUNCTION stockrescue.check_inventory_command_complete() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM stockrescue.stock_movement m
        JOIN stockrescue.stock_movement_line l ON l.org_id=m.org_id AND l.store_id=m.store_id AND l.movement_id=m.id
        WHERE m.org_id=NEW.org_id AND m.store_id=NEW.store_id AND m.command_id=NEW.id) THEN
        RAISE EXCEPTION 'A command outcome requires its durable movement' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER inventory_command_complete AFTER INSERT ON stockrescue.inventory_command_receipt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION stockrescue.check_inventory_command_complete();

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_command_receipt','stock_movement','stock_movement_line','goods_receipt',
        'goods_receipt_line','stock_allocation_transition','inventory_reconciliation_run','inventory_reconciliation_result',
        'inventory_outbox_event','inventory_inbox','inventory_change_feed'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON stockrescue.%I FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation()',table_name||'_immutable',table_name);
        EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON stockrescue.%I FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation()',table_name||'_no_truncate',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['stock_position','stock_allocation','inventory_outbox_delivery'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE DELETE ON stockrescue.%I FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation()',table_name||'_no_delete',table_name);
        EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON stockrescue.%I FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation()',table_name||'_no_truncate',table_name);
    END LOOP;
END;
$$;

COMMENT ON TABLE stockrescue.stock_position IS 'Transactional projection; absent/uninitialized is not verified zero. Physical and bucket quantities reconstruct from the immutable movement lines.';
COMMENT ON TABLE stockrescue.stock_allocation IS 'Disjoint reservation or hold ownership. Current remainder has deferred immutable transition evidence; commands atomically post its corresponding bucket delta.';
COMMENT ON TABLE stockrescue.goods_receipt IS 'Physical delivery evidence with independent document deduplication; never a consignment/source observation or an opening balance import.';
COMMENT ON TABLE stockrescue.inventory_change_feed IS 'Derived read-only Kafka change feed. Its consumer never writes physical stock.';
