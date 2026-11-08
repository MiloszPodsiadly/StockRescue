package com.stockrescue.catalog.application;

import java.util.UUID;

import com.stockrescue.catalog.domain.ProductCategory;

/** A version-bound, durable operator confirmation with an attributable source reference. */
public record FoodSafetyProductClassificationConfirmationSubmission(UUID requestId, long expectedProductVersion,
        ProductCategory category, FoodSafetyProductClassificationConfirmation.SourceType sourceType,
        String evidenceReference, String reason) {

    public boolean hasValidEvidenceShape() {
        boolean sourcePairPresent = sourceType != null && evidenceReference != null && !evidenceReference.isBlank();
        boolean sourcePairAbsent = sourceType == null && evidenceReference == null;
        return category != null && (category == ProductCategory.UNCLASSIFIED
                ? sourcePairPresent || sourcePairAbsent
                : sourcePairPresent);
    }

    public byte[] fingerprint(UUID organizationId, UUID productId) {
        return CatalogCommandDigest.of("stockrescue-product-classification-confirmation-v1",
                organizationId.toString(), productId.toString(), Long.toString(expectedProductVersion),
                category == null ? null : category.name(), sourceType == null ? null : sourceType.name(),
                evidenceReference, reason);
    }
}
