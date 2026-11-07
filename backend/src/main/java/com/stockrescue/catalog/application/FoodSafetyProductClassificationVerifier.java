package com.stockrescue.catalog.application;

import java.time.Instant;
import org.springframework.stereotype.Component;

/** Re-verifies domain-separated product evidence, current trust and exact product revision on every read. */
@Component
public final class FoodSafetyProductClassificationVerifier {
    private final FoodSafetySignatureVerifier signatures;

    public FoodSafetyProductClassificationVerifier(FoodSafetySignatureVerifier signatures) { this.signatures=signatures; }

    public FoodSafetyProductClassificationVerification verify(java.util.UUID organizationId,Product product,
            FoodSafetyProductClassification attestation,FoodSafetyProductClassificationRevocation revocation,Instant now) {
        if (attestation==null) return new FoodSafetyProductClassificationVerification(
                FoodSafetyProductClassificationVerification.Outcome.NOT_SUBMITTED,organizationId,product.id(),null,product.data().sku().value(),
                product.data().category(),product.version(),(com.stockrescue.catalog.domain.ProductCategory)null,(Long)null,(String)null,
                (String)null,(Instant)null,(Instant)null,(Instant)null,(String)null,(Instant)null,false,(Instant)null,(java.util.UUID)null,(String)null);
        var scopeMatches=attestation.organizationId().equals(organizationId) && attestation.productId().equals(product.id())
                && attestation.sku().equals(product.data().sku().value()) && attestation.category()==product.data().category()
                && attestation.productVersion()==product.version() && attestation.attestationVersion()==1;
        var outcome=revocation!=null?FoodSafetyProductClassificationVerification.Outcome.REVOKED
                :!scopeMatches?FoodSafetyProductClassificationVerification.Outcome.PRODUCT_SCOPE_MISMATCH
                :verifySignature(organizationId,product,attestation);
        if (outcome==FoodSafetyProductClassificationVerification.Outcome.VERIFIED && attestation.issuedAt().isAfter(now))
            outcome=FoodSafetyProductClassificationVerification.Outcome.NOT_YET_VALID;
        if (outcome==FoodSafetyProductClassificationVerification.Outcome.VERIFIED && now.isBefore(attestation.validFrom()))
            outcome=FoodSafetyProductClassificationVerification.Outcome.NOT_YET_VALID;
        if (outcome==FoodSafetyProductClassificationVerification.Outcome.VERIFIED && !now.isBefore(attestation.validUntil()))
            outcome=FoodSafetyProductClassificationVerification.Outcome.EXPIRED;
        return new FoodSafetyProductClassificationVerification(outcome,organizationId,product.id(),attestation.id(),product.data().sku().value(),
                product.data().category(),product.version(),attestation.category(),attestation.productVersion(),attestation.issuerId(),
                attestation.keyId(),attestation.issuedAt(),attestation.validFrom(),attestation.validUntil(),attestation.evidenceReference(),
                attestation.recordedAt(),revocation!=null,revocation==null?null:revocation.recordedAt(),
                revocation==null?null:revocation.recordedByMembershipId(),revocation==null?null:revocation.reason());
    }

    private FoodSafetyProductClassificationVerification.Outcome verifySignature(java.util.UUID organizationId,Product product,
            FoodSafetyProductClassification attestation) {
        var outcome=signatures.verify(attestation.issuerId(),attestation.keyId(),
                FoodSafetyProductClassificationAttestation.canonicalPayload(organizationId,product,attestation.category(),
                        attestation.issuerId(),attestation.keyId(),attestation.issuedAt(),attestation.validFrom(),attestation.validUntil(),
                        attestation.evidenceReference()),attestation.signature());
        return FoodSafetyProductClassificationVerification.Outcome.valueOf(outcome.name());
    }
}
