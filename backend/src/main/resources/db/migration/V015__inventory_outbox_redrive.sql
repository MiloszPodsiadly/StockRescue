CREATE TABLE stockrescue.inventory_outbox_redrive_attempt (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    store_id UUID NOT NULL,
    event_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation>0),
    request_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    request_fingerprint BYTEA NOT NULL CHECK (octet_length(request_fingerprint)=32),
    source_fingerprint BYTEA NOT NULL CHECK (octet_length(source_fingerprint)=32),
    source_claim_version BIGINT NOT NULL CHECK (source_claim_version>=0),
    source_attempt_count INTEGER NOT NULL CHECK (source_attempt_count>=0),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason COLLATE "C" !~ '[[:cntrl:]]'),
    evidence_reference VARCHAR(256) NOT NULL CHECK (length(btrim(evidence_reference))>0 AND evidence_reference COLLATE "C" !~ '[[:cntrl:]]'),
    accepted_state VARCHAR(24) NOT NULL CHECK (accepted_state IN ('REDRIVE_QUEUED','BLOCKED')),
    accepted_next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(accepted_next_attempt_at)),
    accepted_blocked_at TIMESTAMPTZ CHECK (accepted_blocked_at IS NULL OR isfinite(accepted_blocked_at)),
    accepted_error_code VARCHAR(64) CHECK (accepted_error_code IS NULL OR accepted_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(next_attempt_at)),
    lease_owner UUID,
    lease_until TIMESTAMPTZ CHECK (lease_until IS NULL OR isfinite(lease_until)),
    claim_version BIGINT NOT NULL DEFAULT 0 CHECK (claim_version>=0),
    published_at TIMESTAMPTZ CHECK (published_at IS NULL OR isfinite(published_at)),
    blocked_at TIMESTAMPTZ CHECK (blocked_at IS NULL OR isfinite(blocked_at)),
    last_error_code VARCHAR(64) CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    last_error_at TIMESTAMPTZ CHECK (last_error_at IS NULL OR isfinite(last_error_at)),
    CONSTRAINT inventory_outbox_redrive_generation_unique UNIQUE (event_id,generation),
    CONSTRAINT inventory_outbox_redrive_request_unique UNIQUE (org_id,actor_user_id,request_id),
    CONSTRAINT inventory_outbox_redrive_lease CHECK ((lease_owner IS NULL)=(lease_until IS NULL)),
    CONSTRAINT inventory_outbox_redrive_published CHECK (published_at IS NULL OR
        (lease_owner IS NULL AND blocked_at IS NULL)),
    CONSTRAINT inventory_outbox_redrive_blocked CHECK (blocked_at IS NULL OR
        (published_at IS NULL AND lease_owner IS NULL AND last_error_code IS NOT NULL AND last_error_at IS NOT NULL)),
    CONSTRAINT inventory_outbox_redrive_accepted_response CHECK (
        (accepted_state='REDRIVE_QUEUED' AND accepted_blocked_at IS NULL AND accepted_error_code IS NULL)
        OR (accepted_state='BLOCKED' AND accepted_blocked_at IS NOT NULL AND accepted_error_code IS NOT NULL)),
    CONSTRAINT inventory_outbox_redrive_event_fk FOREIGN KEY (org_id,store_id,event_id)
        REFERENCES stockrescue.inventory_outbox_event(org_id,store_id,id) ON DELETE RESTRICT,
    CONSTRAINT inventory_outbox_redrive_delivery_fk FOREIGN KEY (event_id)
        REFERENCES stockrescue.inventory_outbox_delivery(event_id) ON DELETE RESTRICT,
    CONSTRAINT inventory_outbox_redrive_store_fk FOREIGN KEY (org_id,store_id)
        REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT,
    CONSTRAINT inventory_outbox_redrive_actor_fk FOREIGN KEY (org_id,membership_id,actor_user_id)
        REFERENCES stockrescue.membership(org_id,id,user_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX inventory_outbox_redrive_one_active_idx
    ON stockrescue.inventory_outbox_redrive_attempt(event_id)
    WHERE published_at IS NULL AND blocked_at IS NULL;
CREATE INDEX inventory_outbox_redrive_claim_idx
    ON stockrescue.inventory_outbox_redrive_attempt(next_attempt_at,lease_until,event_id,generation)
    WHERE published_at IS NULL AND blocked_at IS NULL;
CREATE INDEX inventory_outbox_redrive_scope_idx
    ON stockrescue.inventory_outbox_redrive_attempt(org_id,store_id,event_id,generation DESC);

CREATE FUNCTION stockrescue.guard_inventory_outbox_redrive_attempt() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE original record; expected_generation BIGINT; new_claim BOOLEAN; release_claim BOOLEAN;
BEGIN
    IF TG_OP='INSERT' THEN
        SELECT d.* INTO original FROM stockrescue.inventory_outbox_delivery d
        WHERE d.event_id=NEW.event_id FOR UPDATE;
        IF NOT FOUND OR (original.org_id,original.store_id) IS DISTINCT FROM (NEW.org_id,NEW.store_id)
            OR original.parked_at IS NULL OR original.published_at IS NOT NULL
            OR original.claim_version<>NEW.source_claim_version OR original.attempt_count<>NEW.source_attempt_count THEN
            RAISE EXCEPTION 'A redrive must reference the current terminal parked delivery' USING ERRCODE='23514';
        END IF;
        IF EXISTS (SELECT 1 FROM stockrescue.inventory_outbox_redrive_attempt a
                WHERE a.event_id=NEW.event_id AND a.published_at IS NOT NULL)
            OR EXISTS (SELECT 1 FROM stockrescue.inventory_outbox_redrive_attempt a
                WHERE a.event_id=NEW.event_id AND a.published_at IS NULL AND a.blocked_at IS NULL) THEN
            RAISE EXCEPTION 'An acknowledged or active redrive cannot be reactivated' USING ERRCODE='23514';
        END IF;
        SELECT coalesce(max(a.generation),0)+1 INTO expected_generation
        FROM stockrescue.inventory_outbox_redrive_attempt a WHERE a.event_id=NEW.event_id;
        IF NEW.generation<>expected_generation OR NEW.attempt_count<>0 OR NEW.claim_version<>0
            OR NEW.lease_owner IS NOT NULL OR NEW.lease_until IS NOT NULL OR NEW.published_at IS NOT NULL
            OR NEW.next_attempt_at>clock_timestamp()+interval '5 minutes'
            OR (NEW.blocked_at IS NULL AND NEW.accepted_state<>'REDRIVE_QUEUED')
            OR (NEW.blocked_at IS NOT NULL AND NEW.accepted_state<>'BLOCKED')
            OR NEW.accepted_next_attempt_at IS DISTINCT FROM NEW.next_attempt_at
            OR NEW.accepted_blocked_at IS DISTINCT FROM NEW.blocked_at
            OR (NEW.blocked_at IS NULL AND NEW.accepted_error_code IS NOT NULL)
            OR (NEW.blocked_at IS NOT NULL AND NEW.accepted_error_code IS DISTINCT FROM NEW.last_error_code)
            OR (NEW.blocked_at IS NULL AND (NEW.last_error_code IS NOT NULL OR NEW.last_error_at IS NOT NULL))
            OR (NEW.blocked_at IS NOT NULL AND (NEW.last_error_code IS NULL OR NEW.last_error_at IS NULL)) THEN
            RAISE EXCEPTION 'A redrive generation must start queued or explicitly blocked' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    ELSIF TG_OP='UPDATE' THEN
        IF (NEW.id,NEW.org_id,NEW.store_id,NEW.event_id,NEW.generation,NEW.request_id,NEW.actor_user_id,NEW.membership_id,
                NEW.request_fingerprint,NEW.source_fingerprint,NEW.source_claim_version,NEW.source_attempt_count,
                NEW.reason,NEW.evidence_reference,NEW.accepted_state,NEW.accepted_next_attempt_at,
                NEW.accepted_blocked_at,NEW.accepted_error_code,NEW.recorded_at)
                IS DISTINCT FROM (OLD.id,OLD.org_id,OLD.store_id,OLD.event_id,OLD.generation,OLD.request_id,OLD.actor_user_id,OLD.membership_id,
                    OLD.request_fingerprint,OLD.source_fingerprint,OLD.source_claim_version,OLD.source_attempt_count,
                    OLD.reason,OLD.evidence_reference,OLD.accepted_state,OLD.accepted_next_attempt_at,
                    OLD.accepted_blocked_at,OLD.accepted_error_code,OLD.recorded_at)
            OR ((OLD.published_at IS NOT NULL OR OLD.blocked_at IS NOT NULL) AND NEW IS DISTINCT FROM OLD) THEN
            RAISE EXCEPTION 'Redrive request evidence and terminal states are immutable' USING ERRCODE='23514';
        END IF;

        new_claim := OLD.published_at IS NULL AND OLD.blocked_at IS NULL
            AND NEW.claim_version=OLD.claim_version+1 AND NEW.attempt_count=OLD.attempt_count+1
            AND NEW.lease_owner IS NOT NULL AND NEW.lease_until>clock_timestamp()
            AND NEW.lease_until<=clock_timestamp()+interval '5 minutes'
            AND NEW.next_attempt_at<=clock_timestamp()
            AND (OLD.lease_owner IS NULL OR OLD.lease_until<=clock_timestamp())
            AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
            AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
            AND NEW.blocked_at IS NOT DISTINCT FROM OLD.blocked_at
            AND NEW.last_error_code IS NOT DISTINCT FROM OLD.last_error_code
            AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at;
        release_claim := OLD.published_at IS NULL AND OLD.blocked_at IS NULL
            AND NEW.claim_version=OLD.claim_version AND NEW.attempt_count=OLD.attempt_count
            AND OLD.lease_owner IS NOT NULL AND OLD.lease_until>clock_timestamp()
            AND NEW.lease_owner IS NULL AND NEW.lease_until IS NULL
            AND (
                (NEW.published_at IS NOT NULL AND NEW.blocked_at IS NULL
                    AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
                    AND NEW.last_error_code IS NULL AND NEW.last_error_at IS NOT DISTINCT FROM OLD.last_error_at)
                OR (NEW.published_at IS NULL AND NEW.blocked_at IS NOT NULL
                    AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
                    AND NEW.last_error_code IS NOT NULL AND NEW.last_error_at IS NOT NULL
                    AND (OLD.last_error_at IS NULL OR NEW.last_error_at>=OLD.last_error_at))
                OR (NEW.published_at IS NULL AND NEW.blocked_at IS NULL
                    AND NEW.next_attempt_at>OLD.next_attempt_at
                    AND NEW.next_attempt_at<=clock_timestamp()+interval '5 minutes'
                    AND NEW.last_error_code IS NOT NULL AND NEW.last_error_at IS NOT NULL
                    AND (OLD.last_error_at IS NULL OR NEW.last_error_at>=OLD.last_error_at))
            );
        IF NEW.claim_version=OLD.claim_version+1 THEN
            IF NOT new_claim THEN
                RAISE EXCEPTION 'A redrive claim requires a fresh fencing version and bounded lease' USING ERRCODE='23514';
            END IF;
        ELSIF NEW.claim_version=OLD.claim_version THEN
            IF (NEW.attempt_count,NEW.lease_owner,NEW.lease_until,NEW.next_attempt_at,NEW.published_at,
                    NEW.blocked_at,NEW.last_error_code,NEW.last_error_at)
                    IS DISTINCT FROM (OLD.attempt_count,OLD.lease_owner,OLD.lease_until,OLD.next_attempt_at,OLD.published_at,
                        OLD.blocked_at,OLD.last_error_code,OLD.last_error_at)
                AND NOT release_claim THEN
                RAISE EXCEPTION 'Only the current unexpired redrive lease may acknowledge, block or schedule a retry' USING ERRCODE='23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'A redrive fencing version advances by exactly one claim' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    ELSE
        RAISE EXCEPTION 'Redrive generations cannot be deleted' USING ERRCODE='55000';
    END IF;
END;
$$;

CREATE TRIGGER inventory_outbox_redrive_guard
    BEFORE INSERT OR UPDATE OR DELETE ON stockrescue.inventory_outbox_redrive_attempt
    FOR EACH ROW EXECUTE FUNCTION stockrescue.guard_inventory_outbox_redrive_attempt();
CREATE TRIGGER inventory_outbox_redrive_no_truncate
    BEFORE TRUNCATE ON stockrescue.inventory_outbox_redrive_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();

COMMENT ON TABLE stockrescue.inventory_outbox_redrive_attempt IS
    'Append-only operator requests with fenced delivery attempts for the same immutable parked inventory event.';
