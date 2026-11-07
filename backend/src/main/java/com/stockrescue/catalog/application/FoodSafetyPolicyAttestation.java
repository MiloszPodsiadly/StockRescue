package com.stockrescue.catalog.application;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.Operation;

/** Versioned UTF-8 canonical payload used by external Ed25519 issuers. */
public final class FoodSafetyPolicyAttestation {
    private FoodSafetyPolicyAttestation() { }

    public static byte[] canonicalPayload(UUID organizationId,FoodSafetyPolicyVersion policy,byte[] policyDigest,
            String rulesetId,byte[] rulesetDigest,
            Set<Operation> allowedOperations,String issuerId,String keyId,java.time.Instant issuedAt,
            java.time.Instant validFrom,java.time.Instant validUntil,String evidenceReference) {
        var operations=allowedOperations.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        return String.join("\n","stockrescue-food-safety-approval-v2",organizationId.toString(),policy.id().toString(),
                policy.profileId().toString(),Long.toString(policy.version()),policy.productCategory().name(),
                policy.temperatureClass().name(),operations,HexFormat.of().formatHex(policyDigest),rulesetId,
                HexFormat.of().formatHex(rulesetDigest),issuerId,keyId,
                CanonicalAttestationTimestamp.format(issuedAt),CanonicalAttestationTimestamp.format(validFrom),
                CanonicalAttestationTimestamp.format(validUntil),evidenceReference).getBytes(StandardCharsets.UTF_8);
    }
}
