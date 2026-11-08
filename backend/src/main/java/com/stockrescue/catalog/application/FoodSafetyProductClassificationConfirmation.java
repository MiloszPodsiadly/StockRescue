package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.UUID;

import com.stockrescue.catalog.domain.ProductCategory;

/** Attributed operator evidence for a descriptive category, never an independent safety verification. */
public record FoodSafetyProductClassificationConfirmation(UUID id, UUID organizationId, UUID productId, String sku,
        long expectedProductVersion, long productVersion, ProductCategory originalCategory,
        ProductCategory confirmedCategory, SourceType sourceType, String evidenceReference, UUID actorUserId,
        UUID recordedByMembershipId, UUID requestId, String reason, byte[] payloadDigest, Instant recordedAt) {

    public FoodSafetyProductClassificationConfirmation {
        payloadDigest = payloadDigest.clone();
    }

    @Override
    public byte[] payloadDigest() {
        return payloadDigest.clone();
    }

    public enum SourceType { ORIGINAL_PACKAGE, SUPPLIER_DOCUMENT, OTHER }

    public enum Status { NOT_RECORDED, CURRENT_OPERATOR_CONFIRMED, INSUFFICIENT_EVIDENCE, STALE }

    public Status statusFor(Product product) {
        if (!productId.equals(product.id()) || !sku.equals(product.data().sku().value())
                || confirmedCategory != product.data().category() || productVersion != product.version()) {
            return Status.STALE;
        }
        if (confirmedCategory == ProductCategory.UNCLASSIFIED || sourceType == null || evidenceReference == null) {
            return Status.INSUFFICIENT_EVIDENCE;
        }
        return Status.CURRENT_OPERATOR_CONFIRMED;
    }

    public boolean confirms(Product product) {
        return statusFor(product) == Status.CURRENT_OPERATOR_CONFIRMED;
    }
}
