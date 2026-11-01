ALTER TABLE stockrescue.membership DROP CONSTRAINT membership_state_check;
ALTER TABLE stockrescue.membership ADD CONSTRAINT membership_state_check CHECK (state IN ('ACTIVE','PENDING','REVOKED'));
ALTER TABLE stockrescue.membership ADD COLUMN pending_invitation_id UUID;
ALTER TABLE stockrescue.membership ADD CONSTRAINT membership_pending_invitation_fk
    FOREIGN KEY (org_id,pending_invitation_id) REFERENCES stockrescue.organization_invitation(org_id,id) ON DELETE RESTRICT;
ALTER TABLE stockrescue.membership ADD CONSTRAINT membership_pending_review_check
    CHECK ((state='PENDING') = (pending_invitation_id IS NOT NULL) AND (state<>'PENDING' OR org_role='MEMBER'));
ALTER TABLE stockrescue.organization_invitation ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0);

CREATE TABLE stockrescue.access_rate_limit (
    actor_user_id UUID NOT NULL REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('INVITE','REDEEM')),
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts>0),
    PRIMARY KEY (actor_user_id,operation)
);
