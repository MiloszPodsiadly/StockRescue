package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.UUID;
import com.stockrescue.catalog.domain.EvidenceProvenance;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;

/** A recorded review is not independent approval of an executable food-safety rule. */
public record FoodSafetyPolicyVersion(UUID id,UUID profileId,ProductCategory productCategory,TemperatureClass temperatureClass,long version,
                                      PartialDateRule partialDateRule,ApprovalStatus approvalStatus,EvidenceProvenance reviewProvenance,
                                      UUID recordedByMembershipId,Instant recordedAt,String reason) {
    public enum PartialDateRule { NO_NORMALIZATION, END_OF_LABELED_PERIOD }
    public enum ApprovalStatus { UNVERIFIED }
}
