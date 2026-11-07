ALTER TABLE stockrescue.food_safety_policy_approval
    ADD COLUMN attestation_version SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN ruleset_id VARCHAR(96) COLLATE "C",
    ADD COLUMN ruleset_digest BYTEA,
    ADD CONSTRAINT food_safety_policy_approval_ruleset_shape CHECK (
        (attestation_version=1 AND ruleset_id IS NULL AND ruleset_digest IS NULL)
        OR (attestation_version=2 AND ruleset_id IS NOT NULL AND ruleset_digest IS NOT NULL AND octet_length(ruleset_digest)=32)
    );

COMMENT ON COLUMN stockrescue.food_safety_policy_approval.attestation_version IS
    'Version 1 rows are historical legacy evidence and cannot authorize an executable evaluator ruleset.';
COMMENT ON COLUMN stockrescue.food_safety_policy_approval.ruleset_id IS
    'Ruleset identity included in the signed v2 attestation; NULL only for legacy v1 evidence.';
COMMENT ON COLUMN stockrescue.food_safety_policy_approval.ruleset_digest IS
    'SHA-256 of the stable explicit evaluator ruleset manifest included in the signed v2 attestation.';
