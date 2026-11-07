package com.stockrescue.catalog.application;

import com.stockrescue.catalog.application.FoodSafetyPolicyVersion.PartialDateRule;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.domain.EvidenceProvenance;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;

public record FoodSafetyPolicyReview(long expectedVersion,String reason,PartialDateRule partialDateRule,EvidenceProvenance reviewProvenance) {
    public FoodSafetyPolicyReview {
        if (expectedVersion<0 || expectedVersion==Long.MAX_VALUE || reason==null || reason.isBlank() || reason.length()>500
                || reason.codePoints().anyMatch(Character::isISOControl) || partialDateRule==null
                || reviewProvenance==null || reviewProvenance.reference()==null) throw new CatalogFailure(CatalogFailure.Kind.INVALID_POLICY);
        reason=reason.strip();
    }
    public byte[] fingerprint(ProductCategory category,TemperatureClass temperature) {
        return CatalogCommandDigest.of(category.name(),temperature.name(),Long.toString(expectedVersion),reason,partialDateRule.name(),reviewProvenance.source(),reviewProvenance.reference());
    }
}
