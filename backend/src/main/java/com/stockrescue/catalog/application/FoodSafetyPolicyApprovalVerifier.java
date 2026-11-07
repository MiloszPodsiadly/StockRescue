package com.stockrescue.catalog.application;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
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

    public FoodSafetyPolicyApprovalVerifier(FoodSafetyApprovalProperties properties) { this.properties=properties; }

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
        var signatureOutcome=verifySignature(approval.issuerId(),approval.keyId(),
                FoodSafetyPolicyAttestation.canonicalPayload(approval.organizationId(),policy,approval.policyDigest(),
                        approval.allowedOperations(),approval.issuerId(),approval.keyId(),approval.issuedAt(),approval.validFrom(),
                        approval.validUntil(),approval.evidenceReference()),approval.signature());
        if (signatureOutcome!=FoodSafetyPolicyApprovalVerification.Outcome.VERIFIED)
            return explanation(policy,approval,null,signatureOutcome);
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

    public FoodSafetyPolicyApprovalVerification.Outcome verifySignature(String issuerId,String keyId,byte[] canonicalPayload,byte[] signature) {
        if (!properties.isEnabled()) return FoodSafetyPolicyApprovalVerification.Outcome.TRUST_REGISTRY_DISABLED;
        var trusted=properties.getTrustedIssuers().get(keyId);
        if (trusted==null || trusted.getIssuerId()==null || !trusted.getIssuerId().equals(issuerId))
            return FoodSafetyPolicyApprovalVerification.Outcome.TRUSTED_ISSUER_UNKNOWN;
        if (!trusted.isEnabled()) return FoodSafetyPolicyApprovalVerification.Outcome.TRUSTED_KEY_DISABLED;
        return signatureValid(canonicalPayload,signature,trusted.getPublicKeyX509())
                ?FoodSafetyPolicyApprovalVerification.Outcome.VERIFIED
                :FoodSafetyPolicyApprovalVerification.Outcome.SIGNATURE_INVALID;
    }

    private boolean signatureValid(byte[] canonicalPayload,byte[] signature,String encodedKey) {
        if (encodedKey==null || encodedKey.isBlank()) return false;
        try {
            var keyBytes=Base64.getDecoder().decode(encodedKey);
            var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
            var verifier=Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(canonicalPayload);
            return verifier.verify(signature);
        } catch (IllegalArgumentException | GeneralSecurityException invalidConfigurationOrSignature) {
            return false;
        }
    }

    private boolean scopeMatches(FoodSafetyPolicyVersion policy,UUID organizationId,FoodSafetyPolicyApproval approval) {
        return approval.organizationId().equals(organizationId)
                && approval.policyVersionId().equals(policy.id()) && approval.profileId().equals(policy.profileId())
                && approval.productCategory()==policy.productCategory() && approval.temperatureClass()==policy.temperatureClass()
                && java.security.MessageDigest.isEqual(approval.policyDigest(),FoodSafetyPolicyDigest.of(policy));
    }

    private FoodSafetyPolicyApprovalVerification empty(FoodSafetyPolicyVersion policy,FoodSafetyPolicyApprovalVerification.Outcome outcome) {
        return new FoodSafetyPolicyApprovalVerification(outcome,policy.version(),FoodSafetyPolicyDigest.hex(policy),null,null,
                List.of(),null,null,null,null,null,false,null,null,null);
    }

    private FoodSafetyPolicyApprovalVerification explanation(FoodSafetyPolicyVersion policy,FoodSafetyPolicyApproval approval,
            FoodSafetyPolicyApprovalRevocation revocation,FoodSafetyPolicyApprovalVerification.Outcome outcome) {
        return new FoodSafetyPolicyApprovalVerification(outcome,policy.version(),HexFormat.of().formatHex(approval.policyDigest()),
                approval.issuerId(),approval.keyId(),approval.allowedOperations().stream().sorted().toList(),approval.issuedAt(),
                approval.validFrom(),approval.validUntil(),approval.evidenceReference(),approval.recordedAt(),revocation!=null,
                revocation==null?null:revocation.recordedAt(),revocation==null?null:revocation.recordedByMembershipId(),
                revocation==null?null:revocation.reason());
    }
}
