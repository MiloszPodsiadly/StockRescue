CREATE TABLE stockrescue.organization_onboarding (
    user_id UUID PRIMARY KEY REFERENCES stockrescue.user_account(id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    requested_name VARCHAR(160) NOT NULL CHECK (length(btrim(requested_name)) > 0),
    org_id UUID NOT NULL UNIQUE REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER organization_onboarding_immutable BEFORE UPDATE OR DELETE ON stockrescue.organization_onboarding
    FOR EACH ROW EXECUTE FUNCTION stockrescue.reject_history_mutation();
CREATE TRIGGER organization_onboarding_no_truncate BEFORE TRUNCATE ON stockrescue.organization_onboarding
    FOR EACH STATEMENT EXECUTE FUNCTION stockrescue.reject_history_mutation();
