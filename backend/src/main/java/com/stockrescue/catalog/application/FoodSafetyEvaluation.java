package com.stockrescue.catalog.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.stockrescue.catalog.domain.EvidenceProvenance;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;

public record FoodSafetyEvaluation(Decision decision,List<ReasonCode> reasonCodes,Operation operation,UUID lotId,long lotVersion,
                                   UUID productId,String productSku,long productVersion,UUID storeId,long storeVersion,UUID zoneId,Long zoneVersion,
                                   UUID locationId,Long locationVersion,
                                   ProductCategory productCategory,TemperatureClass productTemperatureClass,
                                   PolicyReference policy,
                                   FoodSafetyProductClassificationConfirmation operatorClassificationConfirmation,
                                   FoodSafetyProductClassificationConfirmation.Status operatorClassificationStatus,
                                   Instant evaluatedAt,LocalDate storeLocalDate,String storeTimeZone,
                                   EvidenceAssessment evidence,NextAction nextAction,String evaluatorVersion,
                                   AssessmentBasis assessmentBasis,String rulesetId,String rulesetDigest,String assessmentNotice) {
    public static final String ALLOWED_NOTICE = "Passed the limited StockRescue checks on the available evidence at this time. This point-in-time assessment does not authorize sale, donation, transfer or stock movement.";
    public FoodSafetyEvaluation { reasonCodes=List.copyOf(reasonCodes); }
    public enum Decision { ALLOWED, DENIED, REQUIRES_REVIEW }
    public enum Operation { SALE, TRANSFER }
    public enum AssessmentBasis { PLATFORM_RULESET }
    // Order is the public explanation priority: hard denials, missing lot/date evidence, storage, then policy review.
    public enum ReasonCode {
        LOT_RECALLED(true), LOT_DAMAGED(true), LOT_ARCHIVED(true), PRODUCT_ARCHIVED(true), STORE_ARCHIVED(true), ZONE_ARCHIVED(true), LOCATION_ARCHIVED(true),
        TEMPERATURE_CLASS_MISMATCH(true), USE_BY_EXPIRED(true), BEST_BEFORE_EXPIRED(true),
        LOT_IDENTITY_UNVERIFIED, DATE_LABEL_MISSING, DATE_TYPE_UNKNOWN, DATE_PRECISION_UNKNOWN, DATE_LABEL_INVALID,
        USE_BY_DAY_REQUIRED, DATE_TYPE_PRECISION_MISMATCH, DATE_PROVENANCE_MISSING, DATE_REFERENCE_MISSING, PARTIAL_DATE_RULE_UNVERIFIED,
        RECALL_STATUS_UNKNOWN, DAMAGE_STATUS_UNKNOWN, STATUS_REFERENCE_MISSING, STORAGE_CONTEXT_MISSING, COLD_CHAIN_EVIDENCE_UNVERIFIED,
        PRODUCT_CATEGORY_UNCLASSIFIED, PRODUCT_CLASSIFICATION_MISSING, PRODUCT_CLASSIFICATION_OPERATOR_CONFIRMATION_STALE,
        PLATFORM_RULESET_SCOPE_UNSUPPORTED, POLICY_MISSING, POLICY_SCOPE_MISMATCH, POLICY_CLASS_MISMATCH;
        private final boolean hardDenial;
        ReasonCode() { this(false); }
        ReasonCode(boolean hardDenial) { this.hardDenial=hardDenial; }
        public boolean hardDenial() { return hardDenial; }
    }
    public enum DateEvidenceStatus { COMPLETE_LABEL, INCOMPLETE, INVALID, PARTIAL_REQUIRES_VERIFIED_RULE }
    public enum StatusEvidenceStatus { UNKNOWN, NOT_REPORTED_IN_REVIEWED_EVIDENCE, BLOCK_RECORDED }
    public enum StorageEvidenceStatus { MISSING, DECLARED_COMPATIBLE, DECLARED_INCOMPATIBLE, INACTIVE }
    public enum PolicyEvidenceStatus { MISSING, RECORDED_UNVERIFIED, PLATFORM_RULESET }
    public enum ProductClassificationEvidenceStatus { MISSING, RECORDED_UNVERIFIED, OPERATOR_CONFIRMED }
    public enum NextAction { DO_NOT_EXECUTE, REVIEW_LOT_EVIDENCE, RECORD_STORAGE_CONTEXT, CONFIRM_PRODUCT_CLASSIFICATION,
        REVIEW_RULESET_SCOPE, NO_ADDITIONAL_ACTION }
    public record PolicyReference(UUID id,UUID profileId,ProductCategory productCategory,long version) { }
    public record EvidenceAssessment(DateEvidenceStatus date,StatusEvidenceStatus recall,StatusEvidenceStatus damage,
                                     StorageEvidenceStatus storage,PolicyEvidenceStatus policy,ProductClassificationEvidenceStatus classification,
                                     EvidenceProvenance manufacturerSource,EvidenceProvenance dateLabelSource,EvidenceProvenance safetyStatusSource) { }
}
