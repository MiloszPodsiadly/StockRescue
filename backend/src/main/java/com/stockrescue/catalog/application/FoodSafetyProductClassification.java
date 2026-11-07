package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.UUID;
import com.stockrescue.catalog.domain.ProductCategory;

/** Immutable external evidence bound to the exact organization-scoped product category revision. */
public record FoodSafetyProductClassification(UUID id,UUID organizationId,UUID productId,String sku,ProductCategory category,
        long productVersion,int attestationVersion,String issuerId,String keyId,Instant issuedAt,Instant validFrom,Instant validUntil,
        String evidenceReference,byte[] signature,UUID actorUserId,UUID recordedByMembershipId,UUID requestId,
        byte[] payloadDigest,Instant recordedAt) {
    public FoodSafetyProductClassification { signature=signature.clone();payloadDigest=payloadDigest.clone(); }
    @Override public byte[] signature() { return signature.clone(); }
    @Override public byte[] payloadDigest() { return payloadDigest.clone(); }
}
