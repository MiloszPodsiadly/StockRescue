package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import com.stockrescue.catalog.domain.CatalogFailure;

/** Signed request for the product's server-selected current category and version. */
public record FoodSafetyProductClassificationSubmission(UUID requestId,long expectedProductVersion,String issuerId,String keyId,
        Instant issuedAt,Instant validFrom,Instant validUntil,String evidenceReference,String signatureBase64) {
    public FoodSafetyProductClassificationSubmission {
        if (requestId==null || expectedProductVersion<0 || expectedProductVersion==Long.MAX_VALUE
                || !identifier(issuerId) || !identifier(keyId) || issuedAt==null || validFrom==null || validUntil==null
                || !validUntil.isAfter(validFrom) || issuedAt.isAfter(validUntil)
                || !CanonicalAttestationTimestamp.isDatabaseRepresentable(issuedAt)
                || !CanonicalAttestationTimestamp.isDatabaseRepresentable(validFrom)
                || !CanonicalAttestationTimestamp.isDatabaseRepresentable(validUntil)
                || evidenceReference==null || evidenceReference.isBlank() || evidenceReference.length()>256
                || evidenceReference.codePoints().anyMatch(Character::isISOControl) || signatureBase64==null) invalid();
        try {
            var decoded=Base64.getDecoder().decode(signatureBase64);
            if (decoded.length!=64 || !Base64.getEncoder().encodeToString(decoded).equals(signatureBase64)) invalid();
        } catch (IllegalArgumentException malformed) { invalid(); }
    }

    public byte[] signature() { return Base64.getDecoder().decode(signatureBase64); }

    public byte[] fingerprint(UUID organizationId,UUID productId) {
        return CatalogCommandDigest.of("stockrescue-product-classification-command-v1",organizationId.toString(),productId.toString(),
                requestId.toString(),Long.toString(expectedProductVersion),issuerId,keyId,CanonicalAttestationTimestamp.format(issuedAt),
                CanonicalAttestationTimestamp.format(validFrom),CanonicalAttestationTimestamp.format(validUntil),evidenceReference,signatureBase64);
    }

    private static boolean identifier(String value) {
        return value!=null && !value.isEmpty() && value.length()<=128 && value.matches("[A-Za-z0-9._:-]+");
    }
    private static void invalid() { throw new CatalogFailure(CatalogFailure.Kind.INVALID_POLICY); }
}
