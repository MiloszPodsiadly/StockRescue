package com.stockrescue.catalog.application;

import java.util.UUID;

import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmation.ProductProfile;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;

/** A version-bound, durable operator confirmation of category, temperature and a bounded product profile. */
public record FoodSafetyProductClassificationConfirmationSubmission(UUID requestId, long expectedProductVersion,
        ProductCategory category, TemperatureClass temperatureClass, ProductProfile productProfile,
        FoodSafetyProductClassificationConfirmation.SourceType sourceType,
        String evidenceReference, String reason) {

    public boolean hasValidEvidenceShape() {
        boolean sourcePairPresent = sourceType != null && evidenceReference != null && !evidenceReference.isBlank();
        return category != null && temperatureClass != null && productProfile != null && sourcePairPresent;
    }

    public byte[] fingerprint(UUID organizationId, UUID productId) {
        return CatalogCommandDigest.of("stockrescue-product-safety-attributes-confirmation-v2",
                organizationId.toString(), productId.toString(), Long.toString(expectedProductVersion),
                category == null ? null : category.name(), temperatureClass == null ? null : temperatureClass.name(),
                productProfile == null ? null : productProfile.name(), sourceType == null ? null : sourceType.name(),
                evidenceReference, reason);
    }
}
