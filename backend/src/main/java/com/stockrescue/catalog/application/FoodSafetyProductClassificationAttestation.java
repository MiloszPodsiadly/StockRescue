package com.stockrescue.catalog.application;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.stockrescue.catalog.domain.ProductCategory;

/** Domain-separated canonical UTF-8 payload for one externally attested product classification. */
public final class FoodSafetyProductClassificationAttestation {
    private FoodSafetyProductClassificationAttestation() { }

    public static byte[] canonicalPayload(UUID organizationId,Product product,ProductCategory category,
            String issuerId,String keyId,java.time.Instant issuedAt,java.time.Instant validFrom,
            java.time.Instant validUntil,String evidenceReference) {
        return String.join("\n","stockrescue-product-classification-v1",organizationId.toString(),product.id().toString(),
                product.data().sku().value(),category.name(),Long.toString(product.version()),issuerId,keyId,
                CanonicalAttestationTimestamp.format(issuedAt),CanonicalAttestationTimestamp.format(validFrom),
                CanonicalAttestationTimestamp.format(validUntil),evidenceReference).getBytes(StandardCharsets.UTF_8);
    }
}
