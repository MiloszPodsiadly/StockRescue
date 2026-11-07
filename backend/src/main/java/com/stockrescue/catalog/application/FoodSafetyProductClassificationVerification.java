package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.UUID;
import com.stockrescue.catalog.domain.ProductCategory;

/** Request-time verification details. It is evidence, never an authorization token. */
public record FoodSafetyProductClassificationVerification(Outcome outcome,UUID organizationId,UUID productId,UUID attestationId,
        String currentSku,ProductCategory currentCategory,long currentProductVersion,ProductCategory signedCategory,
        Long signedProductVersion,String issuerId,String keyId,Instant issuedAt,Instant validFrom,Instant validUntil,
        String evidenceReference,Instant recordedAt,boolean revoked,Instant revokedAt,UUID revokedByMembershipId,String revocationReason) {
    public enum Outcome {
        NOT_SUBMITTED, TRUST_REGISTRY_DISABLED, TRUSTED_ISSUER_UNKNOWN, TRUSTED_KEY_DISABLED, SIGNATURE_INVALID,
        NOT_YET_VALID, EXPIRED, REVOKED, PRODUCT_SCOPE_MISMATCH, VERIFIED
    }

    public boolean authorizes(Product product) {
        return outcome==Outcome.VERIFIED && organizationId!=null && attestationId!=null && productId.equals(product.id())
                && currentSku.equals(product.data().sku().value()) && currentCategory==product.data().category()
                && currentProductVersion==product.version() && signedCategory==product.data().category()
                && signedProductVersion!=null && signedProductVersion==product.version();
    }
}
