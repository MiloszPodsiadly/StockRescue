CREATE TABLE stockrescue.inventory_projection_repair (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    run_id UUID NOT NULL,
    position_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    prior_version BIGINT NOT NULL CHECK (prior_version>=0),
    repaired_version BIGINT NOT NULL CHECK (repaired_version=prior_version+1),
    on_hand BIGINT NOT NULL CHECK (on_hand>=0),
    reserved BIGINT NOT NULL CHECK (reserved>=0),
    quarantined BIGINT NOT NULL CHECK (quarantined>=0),
    other_blocked BIGINT NOT NULL CHECK (other_blocked>=0),
    trust_state VARCHAR(16) NOT NULL CHECK (trust_state='CLEAN'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    evidence_reference VARCHAR(256) NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference COLLATE "C" !~ '[[:cntrl:]]'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    CONSTRAINT inventory_projection_repair_buckets CHECK (reserved::numeric+quarantined+other_blocked<=on_hand),
    CONSTRAINT inventory_projection_repair_version UNIQUE (org_id,store_id,position_id,repaired_version),
    CONSTRAINT inventory_projection_repair_scope UNIQUE (org_id,store_id,id,position_id,repaired_version),
    CONSTRAINT inventory_projection_repair_run_fk FOREIGN KEY (org_id,store_id,run_id,position_id)
        REFERENCES stockrescue.inventory_reconciliation_result(org_id,store_id,run_id,position_id) ON DELETE RESTRICT,
    CONSTRAINT inventory_projection_repair_position_fk FOREIGN KEY (org_id,store_id,position_id)
        REFERENCES stockrescue.stock_position(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT inventory_projection_repair_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT
);
CREATE INDEX inventory_reconciliation_run_page_idx ON stockrescue.inventory_reconciliation_run(org_id,store_id,completed_at DESC,id DESC);
CREATE INDEX inventory_reconciliation_allocation_idx ON stockrescue.stock_allocation(org_id,store_id,position_id,id);
CREATE INDEX inventory_reconciliation_transition_idx ON stockrescue.stock_allocation_transition(org_id,store_id,position_id,allocation_id,version);

CREATE OR REPLACE FUNCTION stockrescue.guard_stock_position() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE repair stockrescue.inventory_projection_repair%ROWTYPE;
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
        SELECT r.* INTO repair FROM stockrescue.inventory_projection_repair r
        JOIN stockrescue.stock_movement_line l ON l.org_id=r.org_id AND l.store_id=r.store_id
            AND l.position_id=r.position_id AND l.position_version=r.repaired_version
        JOIN stockrescue.stock_movement m ON m.org_id=l.org_id AND m.store_id=l.store_id AND m.id=l.movement_id
        WHERE r.org_id=NEW.org_id AND r.store_id=NEW.store_id AND r.position_id=NEW.id
            AND r.prior_version=OLD.version AND r.repaired_version=NEW.version AND m.operation='REMEDIATE'
            AND r.on_hand=NEW.on_hand AND r.reserved=NEW.reserved AND r.quarantined=NEW.quarantined
            AND r.other_blocked=NEW.other_blocked AND r.trust_state=NEW.trust_state
            AND NEW.initialization_state='INITIALIZED'
            AND EXISTS (SELECT 1 FROM stockrescue.inventory_reconciliation_result rr
                WHERE rr.org_id=r.org_id AND rr.store_id=r.store_id AND rr.run_id=r.run_id
                  AND rr.position_id=r.position_id AND rr.severity='CRITICAL')
            AND r.on_hand=coalesce((SELECT sum(x.delta_on_hand::numeric) FROM stockrescue.stock_movement_line x
                WHERE x.org_id=r.org_id AND x.store_id=r.store_id AND x.position_id=r.position_id AND x.position_version<NEW.version),0)
            AND r.reserved=coalesce((SELECT sum(x.delta_reserved::numeric) FROM stockrescue.stock_movement_line x
                WHERE x.org_id=r.org_id AND x.store_id=r.store_id AND x.position_id=r.position_id AND x.position_version<NEW.version),0)
            AND r.quarantined=coalesce((SELECT sum(x.delta_quarantined::numeric) FROM stockrescue.stock_movement_line x
                WHERE x.org_id=r.org_id AND x.store_id=r.store_id AND x.position_id=r.position_id AND x.position_version<NEW.version),0)
            AND r.other_blocked=coalesce((SELECT sum(x.delta_other_blocked::numeric) FROM stockrescue.stock_movement_line x
                WHERE x.org_id=r.org_id AND x.store_id=r.store_id AND x.position_id=r.position_id AND x.position_version<NEW.version),0)
            AND r.reserved=coalesce((SELECT sum(a.remaining_quantity::numeric) FROM stockrescue.stock_allocation a
                WHERE a.org_id=r.org_id AND a.store_id=r.store_id AND a.position_id=r.position_id AND a.bucket='RESERVED' AND a.state='ACTIVE'),0)
            AND r.quarantined=coalesce((SELECT sum(a.remaining_quantity::numeric) FROM stockrescue.stock_allocation a
                WHERE a.org_id=r.org_id AND a.store_id=r.store_id AND a.position_id=r.position_id AND a.bucket='QUARANTINED' AND a.state='ACTIVE'),0)
            AND r.other_blocked=coalesce((SELECT sum(a.remaining_quantity::numeric) FROM stockrescue.stock_allocation a
                WHERE a.org_id=r.org_id AND a.store_id=r.store_id AND a.position_id=r.position_id AND a.bucket='OTHER_BLOCKED' AND a.state='ACTIVE'),0)
            AND l.delta_on_hand=0 AND l.delta_reserved=0 AND l.delta_quarantined=0 AND l.delta_other_blocked=0;
        IF OLD.trust_state='FROZEN' AND repair.id IS NULL THEN
            RAISE EXCEPTION 'A frozen position requires an audited projection repair' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM stockrescue.stock_movement_line l
            WHERE l.org_id=NEW.org_id AND l.store_id=NEW.store_id AND l.position_id=NEW.id AND l.position_version=NEW.version
              AND OLD.on_hand::numeric+l.delta_on_hand=NEW.on_hand
              AND OLD.reserved::numeric+l.delta_reserved=NEW.reserved
              AND OLD.quarantined::numeric+l.delta_quarantined=NEW.quarantined
              AND OLD.other_blocked::numeric+l.delta_other_blocked=NEW.other_blocked
              AND l.initialization_after=NEW.initialization_state AND l.trust_after=NEW.trust_state)
            AND repair.id IS NULL THEN
            RAISE EXCEPTION 'A projection update requires matching immutable movement evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER inventory_projection_repair_immutable BEFORE UPDATE OR DELETE ON stockrescue.inventory_projection_repair
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER inventory_projection_repair_no_truncate BEFORE TRUNCATE ON stockrescue.inventory_projection_repair
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
