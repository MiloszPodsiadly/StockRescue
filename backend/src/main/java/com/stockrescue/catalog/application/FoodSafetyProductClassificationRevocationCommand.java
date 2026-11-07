package com.stockrescue.catalog.application;

import java.util.UUID;
import com.stockrescue.catalog.domain.CatalogFailure;

public record FoodSafetyProductClassificationRevocationCommand(UUID requestId,String reason) {
    public FoodSafetyProductClassificationRevocationCommand {
        if (requestId==null || reason==null || reason.isBlank() || reason.length()>500
                || reason.codePoints().anyMatch(Character::isISOControl)) throw new CatalogFailure(CatalogFailure.Kind.INVALID_POLICY);
        reason=reason.strip();
    }
    public byte[] fingerprint(UUID organizationId,UUID productId,UUID attestationId) {
        return CatalogCommandDigest.of("stockrescue-product-classification-revocation-v1",organizationId.toString(),productId.toString(),
                attestationId.toString(),requestId.toString(),reason);
    }
}
