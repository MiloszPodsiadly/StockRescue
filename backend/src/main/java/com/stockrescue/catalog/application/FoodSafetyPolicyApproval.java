package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.Operation;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;

/** Immutable signed approval evidence for one exact reviewed policy version. */
public record FoodSafetyPolicyApproval(UUID id,UUID organizationId,UUID policyVersionId,UUID profileId,
        ProductCategory productCategory,TemperatureClass temperatureClass,Set<Operation> allowedOperations,
        byte[] policyDigest,int attestationVersion,String rulesetId,byte[] rulesetDigest,
        String issuerId,String keyId,Instant issuedAt,Instant validFrom,Instant validUntil,
        String evidenceReference,byte[] signature,UUID actorUserId,UUID recordedByMembershipId,UUID requestId,
        byte[] payloadDigest,Instant recordedAt) {
    public FoodSafetyPolicyApproval {
        allowedOperations=Set.copyOf(allowedOperations);
        policyDigest=policyDigest.clone();
        rulesetDigest=rulesetDigest==null?null:rulesetDigest.clone();
        signature=signature.clone();
        payloadDigest=payloadDigest.clone();
    }
    @Override public byte[] policyDigest() { return policyDigest.clone(); }
    @Override public byte[] rulesetDigest() { return rulesetDigest==null?null:rulesetDigest.clone(); }
    @Override public byte[] signature() { return signature.clone(); }
    @Override public byte[] payloadDigest() { return payloadDigest.clone(); }
}
