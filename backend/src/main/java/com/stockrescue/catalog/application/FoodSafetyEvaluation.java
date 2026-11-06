package com.stockrescue.catalog.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FoodSafetyEvaluation(Decision decision,List<ReasonCode> reasonCodes,Operation operation,UUID lotId,long lotVersion,
                                   UUID productId,long productVersion,UUID storeId,long storeVersion,UUID zoneId,Long zoneVersion,
                                   UUID locationId,Long locationVersion,
                                   PolicyReference policy,Instant evaluatedAt,LocalDate storeLocalDate,String storeTimeZone,
                                   EvidenceAssessment evidence,NextAction nextAction,String evaluatorVersion) {
    public FoodSafetyEvaluation { reasonCodes=List.copyOf(reasonCodes); }
    public enum Decision { ALLOWED, DENIED, REQUIRES_REVIEW }
    public enum Operation { SALE, TRANSFER }
    // Order is the public explanation priority: hard denials, missing lot/date evidence, storage, then policy review.
    public enum ReasonCode {
        LOT_RECALLED(true), LOT_DAMAGED(true), LOT_ARCHIVED(true), PRODUCT_ARCHIVED(true), STORE_ARCHIVED(true), ZONE_ARCHIVED(true), LOCATION_ARCHIVED(true),
        TEMPERATURE_CLASS_MISMATCH(true), USE_BY_EXPIRED(true), BEST_BEFORE_EXPIRED(true),
        LOT_IDENTITY_UNVERIFIED, DATE_LABEL_MISSING, DATE_TYPE_UNKNOWN, DATE_PRECISION_UNKNOWN, DATE_LABEL_INVALID,
        USE_BY_DAY_REQUIRED, DATE_PROVENANCE_MISSING, DATE_REFERENCE_MISSING, PARTIAL_DATE_RULE_UNVERIFIED,
        RECALL_STATUS_UNKNOWN, DAMAGE_STATUS_UNKNOWN, STATUS_REFERENCE_MISSING, STORAGE_CONTEXT_MISSING, COLD_CHAIN_EVIDENCE_UNVERIFIED,
        POLICY_MISSING, POLICY_CLASS_MISMATCH, POLICY_APPROVAL_UNVERIFIED;
        private final boolean hardDenial;
        ReasonCode() { this(false); }
        ReasonCode(boolean hardDenial) { this.hardDenial=hardDenial; }
        public boolean hardDenial() { return hardDenial; }
    }
    public enum DateEvidenceStatus { COMPLETE_LABEL, INCOMPLETE, INVALID, PARTIAL_REQUIRES_VERIFIED_RULE }
    public enum StatusEvidenceStatus { UNKNOWN, REPORTED, BLOCK_RECORDED }
    public enum StorageEvidenceStatus { MISSING, DECLARED_COMPATIBLE, DECLARED_INCOMPATIBLE, INACTIVE }
    public enum PolicyEvidenceStatus { MISSING, RECORDED_UNVERIFIED }
    public enum NextAction { DO_NOT_EXECUTE, REVIEW_LOT_EVIDENCE, RECORD_STORAGE_CONTEXT, OBTAIN_INDEPENDENT_POLICY_APPROVAL }
    public record PolicyReference(UUID id,UUID profileId,long version) { }
    public record EvidenceAssessment(DateEvidenceStatus date,StatusEvidenceStatus recall,StatusEvidenceStatus damage,
                                     StorageEvidenceStatus storage,PolicyEvidenceStatus policy) { }
}
