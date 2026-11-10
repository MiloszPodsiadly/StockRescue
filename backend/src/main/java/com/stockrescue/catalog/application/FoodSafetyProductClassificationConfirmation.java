package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.UUID;

import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;

/** Attributed source evidence for current product safety attributes; never an independent safety verification. */
public record FoodSafetyProductClassificationConfirmation(UUID id, UUID organizationId, UUID productId, String sku,
        long expectedProductVersion, long productVersion, ProductCategory originalCategory,
        ProductCategory confirmedCategory, TemperatureClass originalTemperatureClass,
        TemperatureClass confirmedTemperatureClass, ProductProfile productProfile,
        SourceType sourceType, String evidenceReference, UUID actorUserId,
        UUID recordedByMembershipId, UUID requestId, String reason, byte[] payloadDigest, Instant recordedAt) {

    public FoodSafetyProductClassificationConfirmation {
        payloadDigest = payloadDigest.clone();
    }

    @Override
    public byte[] payloadDigest() {
        return payloadDigest.clone();
    }

    public enum SourceType { ORIGINAL_PACKAGE, SUPPLIER_DOCUMENT, OTHER }

    /** A bounded operator-described product profile. Only the packaged dry profile is in the current pilot. */
    public enum ProductProfile {
        NOT_ASSESSED,
        PACKAGED_SHELF_STABLE_DRY_BAKERY,
        REFRIGERATION_REQUIRED,
        CREAM_FILLED_OR_PERISHABLE,
        NOT_SHELF_STABLE,
        STORAGE_INSTRUCTION_MISSING_OR_AMBIGUOUS,
        CONTRADICTORY_STORAGE_EVIDENCE,
        OUTSIDE_CURRENT_PILOT
    }

    public enum Status { NOT_RECORDED, CURRENT_OPERATOR_CONFIRMED, INSUFFICIENT_EVIDENCE, STALE }

    public Status statusFor(Product product) {
        if (!productId.equals(product.id()) || !sku.equals(product.data().sku().value())
                || confirmedCategory != product.data().category()
                || confirmedTemperatureClass != product.data().temperatureClass()
                || productVersion != product.version()) {
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
