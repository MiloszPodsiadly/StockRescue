package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.Operation;
import org.springframework.stereotype.Component;

/** Re-verifies signatures against deployment-owned trust on every read or evaluation. */
@Component
public final class FoodSafetyPolicyApprovalVerifier {
    private final FoodSafetyApprovalProperties properties;
    private final FoodSafetyRuleset ruleset;
    private final FoodSafetySignatureVerifier signatures;

    public FoodSafetyPolicyApprovalVerifier(FoodSafetyApprovalProperties properties,FoodSafetyRuleset ruleset,
            FoodSafetySignatureVerifier signatures) {
        this.properties=properties;this.ruleset=ruleset;this.signatures=signatures;
    }

    public FoodSafetyPolicyApprovalVerification verify(FoodSafetyPolicyVersion policy,UUID organizationId,
            FoodSafetyPolicyApproval approval,FoodSafetyPolicyApprovalRevocation revocation,Operation operation,Instant now) {
        Objects.requireNonNull(policy); Objects.requireNonNull(organizationId); Objects.requireNonNull(now);
        if (approval==null) return empty(policy,properties.isEnabled()
                ?FoodSafetyPolicyApprovalVerification.Outcome.NOT_SUBMITTED
                :FoodSafetyPolicyApprovalVerification.Outcome.TRUST_REGISTRY_DISABLED);

        boolean revoked=revocation!=null;
        if (!scopeMatches(policy,organizationId,approval)) return explanation(policy,approval,revocation,
                FoodSafetyPolicyApprovalVerification.Outcome.POLICY_SCOPE_MISMATCH);
        if (revoked) return explanation(policy,approval,revocation,FoodSafetyPolicyApprovalVerification.Outcome.REVOKED);
        if (approval.attestationVersion()!=2 || approval.rulesetId()==null || approval.rulesetDigest()==null)
            return explanation(policy,approval,null,FoodSafetyPolicyApprovalVerification.Outcome.LEGACY_ATTESTATION);
        var signatureOutcome=signatures.verify(approval.issuerId(),approval.keyId(),
                FoodSafetyPolicyAttestation.canonicalPayload(approval.organizationId(),policy,approval.policyDigest(),
                        approval.rulesetId(),approval.rulesetDigest(),approval.allowedOperations(),approval.issuerId(),approval.keyId(),approval.issuedAt(),approval.validFrom(),
                        approval.validUntil(),approval.evidenceReference()),approval.signature());
        if (signatureOutcome!=FoodSafetySignatureVerifier.Outcome.VERIFIED)
            return explanation(policy,approval,null,FoodSafetyPolicyApprovalVerification.Outcome.valueOf(signatureOutcome.name()));
        if (!ruleset.matches(approval.rulesetId(),approval.rulesetDigest()))
            return explanation(policy,approval,null,FoodSafetyPolicyApprovalVerification.Outcome.RULESET_MISMATCH);
        if (approval.issuedAt().isAfter(now) || now.isBefore(approval.validFrom())) {
            return explanation(policy,approval,null,FoodSafetyPolicyApprovalVerification.Outcome.NOT_YET_VALID);
        }
        if (!now.isBefore(approval.validUntil())) {
            return explanation(policy,approval,null,FoodSafetyPolicyApprovalVerification.Outcome.EXPIRED);
        }
        if (operation!=null && !approval.allowedOperations().contains(operation)) {
            return explanation(policy,approval,null,FoodSafetyPolicyApprovalVerification.Outcome.OPERATION_NOT_APPROVED);
        }
        return explanation(policy,approval,null,FoodSafetyPolicyApprovalVerification.Outcome.VERIFIED);
    }

    private boolean scopeMatches(FoodSafetyPolicyVersion policy,UUID organizationId,FoodSafetyPolicyApproval approval) {
        return approval.organizationId().equals(organizationId)
                && approval.policyVersionId().equals(policy.id()) && approval.profileId().equals(policy.profileId())
                && approval.productCategory()==policy.productCategory() && approval.temperatureClass()==policy.temperatureClass()
                && java.security.MessageDigest.isEqual(approval.policyDigest(),FoodSafetyPolicyDigest.of(policy));
    }

    private FoodSafetyPolicyApprovalVerification empty(FoodSafetyPolicyVersion policy,FoodSafetyPolicyApprovalVerification.Outcome outcome) {
        return new FoodSafetyPolicyApprovalVerification(outcome,policy.version(),FoodSafetyPolicyDigest.hex(policy),null,null,
                List.of(),null,null,null,null,null,false,null,null,null,null,null,null,ruleset.id(),ruleset.digestHex());
    }

    private FoodSafetyPolicyApprovalVerification explanation(FoodSafetyPolicyVersion policy,FoodSafetyPolicyApproval approval,
            FoodSafetyPolicyApprovalRevocation revocation,FoodSafetyPolicyApprovalVerification.Outcome outcome) {
        return new FoodSafetyPolicyApprovalVerification(outcome,policy.version(),HexFormat.of().formatHex(approval.policyDigest()),
                approval.issuerId(),approval.keyId(),approval.allowedOperations().stream().sorted().toList(),approval.issuedAt(),
                approval.validFrom(),approval.validUntil(),approval.evidenceReference(),approval.recordedAt(),revocation!=null,
                revocation==null?null:revocation.recordedAt(),revocation==null?null:revocation.recordedByMembershipId(),
                revocation==null?null:revocation.reason(),approval.attestationVersion(),approval.rulesetId(),
                approval.rulesetDigest()==null?null:HexFormat.of().formatHex(approval.rulesetDigest()),ruleset.id(),ruleset.digestHex());
    }
}
