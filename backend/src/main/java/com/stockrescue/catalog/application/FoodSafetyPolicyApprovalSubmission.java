package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.Operation;
import com.stockrescue.catalog.domain.CatalogFailure;

/** Signed attestation fields; the request never contains a verification-status flag or trust key. */
public record FoodSafetyPolicyApprovalSubmission(UUID requestId,String issuerId,String keyId,String policyDigest,
        String rulesetId,String rulesetDigest,
        Set<Operation> allowedOperations,Instant issuedAt,Instant validFrom,Instant validUntil,String evidenceReference,
        String signatureBase64) {
    public FoodSafetyPolicyApprovalSubmission {
        if (requestId==null || !identifier(issuerId,128) || !identifier(keyId,128)
                || policyDigest==null || !policyDigest.matches("[0-9a-f]{64}")
                || !identifier(rulesetId,96) || rulesetDigest==null || !rulesetDigest.matches("[0-9a-f]{64}")
                || allowedOperations==null || allowedOperations.isEmpty() || allowedOperations.stream().anyMatch(java.util.Objects::isNull)
                || issuedAt==null || validFrom==null || validUntil==null || !validUntil.isAfter(validFrom) || issuedAt.isAfter(validUntil)
                || !CanonicalAttestationTimestamp.isDatabaseRepresentable(issuedAt)
                || !CanonicalAttestationTimestamp.isDatabaseRepresentable(validFrom)
                || !CanonicalAttestationTimestamp.isDatabaseRepresentable(validUntil)
                || evidenceReference==null || evidenceReference.isBlank() || evidenceReference.length()>256
                || evidenceReference.codePoints().anyMatch(Character::isISOControl) || signatureBase64==null) invalid();
        allowedOperations=Set.copyOf(allowedOperations);
        try {
            var decoded=Base64.getDecoder().decode(signatureBase64);
            if (decoded.length!=64 || !Base64.getEncoder().encodeToString(decoded).equals(signatureBase64)) invalid();
        } catch (IllegalArgumentException malformed) { invalid(); }
    }
    public byte[] signature() { return Base64.getDecoder().decode(signatureBase64); }
    public byte[] fingerprint(UUID organizationId,FoodSafetyPolicyVersion policy) {
        return CatalogCommandDigest.of(organizationId.toString(),policy.id().toString(),policy.profileId().toString(),
                policy.productCategory().name(),policy.temperatureClass().name(),requestId.toString(),issuerId,keyId,policyDigest,
                rulesetId,rulesetDigest,
                allowedOperations.stream().map(Enum::name).sorted().collect(Collectors.joining(",")),
                CanonicalAttestationTimestamp.format(issuedAt),CanonicalAttestationTimestamp.format(validFrom),
                CanonicalAttestationTimestamp.format(validUntil),evidenceReference,signatureBase64);
    }
    private static boolean identifier(String value,int maximum) {
        return value!=null && value.length()>0 && value.length()<=maximum && value.matches("[A-Za-z0-9._:-]+");
    }
    private static void invalid() { throw new CatalogFailure(CatalogFailure.Kind.INVALID_POLICY); }
}
