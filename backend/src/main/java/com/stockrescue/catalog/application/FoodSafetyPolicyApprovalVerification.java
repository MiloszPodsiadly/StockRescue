package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.Operation;

/** Read-only, request-time verification explanation. It is never a reusable authorization token. */
public record FoodSafetyPolicyApprovalVerification(Outcome outcome,long policyVersion,String policyDigest,
        String issuerId,String keyId,List<Operation> allowedOperations,Instant issuedAt,Instant validFrom,Instant validUntil,
        String evidenceReference,Instant recordedAt,boolean revoked,Instant revokedAt,UUID revokedByMembershipId,
        String revocationReason,Integer attestationVersion,String signedRulesetId,String signedRulesetDigest,
        String activeRulesetId,String activeRulesetDigest) {
    public enum Outcome {
        NOT_SUBMITTED, TRUST_REGISTRY_DISABLED, TRUSTED_ISSUER_UNKNOWN, TRUSTED_KEY_DISABLED, SIGNATURE_INVALID,
        NOT_YET_VALID, EXPIRED, REVOKED, POLICY_SCOPE_MISMATCH, LEGACY_ATTESTATION, RULESET_MISMATCH,
        OPERATION_NOT_APPROVED, VERIFIED
    }
    public FoodSafetyPolicyApprovalVerification { allowedOperations=List.copyOf(allowedOperations); }
    public boolean authorizes(Operation operation) { return outcome==Outcome.VERIFIED && allowedOperations.contains(operation); }
}
